using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using PocketPad.Core.Protocol;

namespace PocketPad.Windows.Services;

public sealed record NetworkEndpointInfo(string InterfaceName, string IpAddress, bool IsUsbTethering);

/// <summary>
/// High-speed TCP listener (port 27183, TCP_NODELAY) serving USB ADB Reverse, USB Tethering, and LAN/Wi-Fi,
/// paired with a UDP discovery responder (port 27184) so Android finds the PC automatically.
/// </summary>
public sealed class TcpUsbServer : IDisposable
{
    private readonly Action<string> _log;
    private readonly Func<bool> _isViGemReady;
    private readonly Func<int> _getConnectedCount;
    private readonly Func<Stream, string, string, CancellationToken, Task> _onClientConnected;
    private readonly CancellationTokenSource _cts = new();

    private TcpListener? _tcpListener;
    private UdpClient? _udpDiscovery;
    private Task? _tcpTask;
    private Task? _udpTask;

    public bool IsListening { get; private set; }

    public TcpUsbServer(
        Action<string> log,
        Func<bool> isViGemReady,
        Func<int> getConnectedCount,
        Func<Stream, string, string, CancellationToken, Task> onClientConnected)
    {
        _log = log;
        _isViGemReady = isViGemReady;
        _getConnectedCount = getConnectedCount;
        _onClientConnected = onClientConnected;
    }

    public void Start()
    {
        try
        {
            _tcpListener = new TcpListener(IPAddress.Any, PocketPadConstants.TcpPort);
            _tcpListener.Server.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            _tcpListener.Start();
            IsListening = true;
            _log($"Serveur USB / Réseau actif sur le port TCP {PocketPadConstants.TcpPort}");
            _tcpTask = Task.Run(() => AcceptTcpLoopAsync(_cts.Token));
        }
        catch (Exception ex)
        {
            IsListening = false;
            _log($"Erreur démarrage serveur TCP {PocketPadConstants.TcpPort} : {ex.Message}");
        }

        try
        {
            _udpDiscovery = new UdpClient();
            _udpDiscovery.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            _udpDiscovery.Client.Bind(new IPEndPoint(IPAddress.Any, PocketPadConstants.UdpDiscoveryPort));
            _udpDiscovery.EnableBroadcast = true;
            _udpTask = Task.Run(() => UdpDiscoveryLoopAsync(_cts.Token));
        }
        catch (Exception ex)
        {
            _log($"Avertissement découverte UDP {PocketPadConstants.UdpDiscoveryPort} : {ex.Message}");
        }
    }

    private async Task AcceptTcpLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested && _tcpListener is not null)
        {
            try
            {
                var client = await _tcpListener.AcceptTcpClientAsync(ct);
                client.NoDelay = true;
                client.ReceiveBufferSize = 8192;
                client.SendBufferSize = 4096;

                var remoteEp = client.Client.RemoteEndPoint as IPEndPoint;
                var remoteIp = remoteEp?.Address.ToString() ?? "inconnu";
                var isLoopback = remoteEp is not null && IPAddress.IsLoopback(remoteEp.Address);
                var transportGuess = isLoopback ? "USB (ADB)" : IsUsbSubnet(remoteIp) ? "USB (Partage)" : "Wi-Fi / Réseau";

                _ = Task.Run(async () =>
                {
                    using (client)
                    using (var stream = client.GetStream())
                    {
                        await _onClientConnected(stream, transportGuess, remoteIp, ct);
                    }
                }, ct);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (ObjectDisposedException)
            {
                break;
            }
            catch (Exception ex)
            {
                _log($"Erreur acceptation TCP : {ex.Message}");
            }
        }
    }

    private async Task UdpDiscoveryLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested && _udpDiscovery is not null)
        {
            try
            {
                var result = await _udpDiscovery.ReceiveAsync(ct);
                var msg = Encoding.UTF8.GetString(result.Buffer).Trim();
                if (!string.Equals(msg, PocketPadConstants.DiscoveryRequest, StringComparison.Ordinal))
                    continue;

                var pcName = Environment.MachineName.Replace('|', '_');
                var reply = $"{PocketPadConstants.DiscoveryResponsePrefix}|{pcName}|{PocketPadConstants.TcpPort}|{(_isViGemReady() ? "1" : "0")}|{_getConnectedCount()}|{PocketPadConstants.MaxPlayers}";
                var bytes = Encoding.UTF8.GetBytes(reply);
                await _udpDiscovery.SendAsync(bytes, bytes.Length, result.RemoteEndPoint);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (ObjectDisposedException)
            {
                break;
            }
            catch
            {
                // Ignore transient UDP socket errors
            }
        }
    }

    private static bool IsUsbSubnet(string ip)
    {
        return ip.StartsWith("192.168.42.", StringComparison.Ordinal) ||
               ip.StartsWith("192.168.43.", StringComparison.Ordinal) ||
               ip.StartsWith("172.20.10.", StringComparison.Ordinal);
    }

    public static IReadOnlyList<NetworkEndpointInfo> GetLocalNetworkAddresses()
    {
        var list = new List<NetworkEndpointInfo>();
        try
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up) continue;
                if (ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;

                var desc = $"{ni.Name} {ni.Description}".ToLowerInvariant();
                if (desc.Contains("virtualbox") || desc.Contains("vmware") || desc.Contains("vethernet") || desc.Contains("wsl"))
                    continue;

                var isUsb = desc.Contains("rndis") || desc.Contains("usb") || desc.Contains("android") || desc.Contains("samsung") || desc.Contains("remote ndis");

                foreach (var ua in ni.GetIPProperties().UnicastAddresses)
                {
                    if (ua.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                    var ip = ua.Address.ToString();
                    if (ip.StartsWith("169.254.", StringComparison.Ordinal)) continue;
                    if (!isUsb && IsUsbSubnet(ip)) isUsb = true;
                    list.Add(new NetworkEndpointInfo(ni.Name, ip, isUsb));
                }
            }
        }
        catch
        {
            // Ignore adapter enumeration issues
        }

        return list.OrderByDescending(x => x.IsUsbTethering).ThenBy(x => x.IpAddress).ToList();
    }

    public void Dispose()
    {
        _cts.Cancel();
        try { _tcpListener?.Stop(); } catch { }
        try { _udpDiscovery?.Close(); } catch { }
        try { _udpDiscovery?.Dispose(); } catch { }
        _cts.Dispose();
    }
}
