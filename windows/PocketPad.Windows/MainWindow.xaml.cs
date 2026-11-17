using System.ComponentModel;
using System.Windows;
using System.Windows.Media;
using PocketPad.Core.Protocol;
using PocketPad.Windows.Services;

namespace PocketPad.Windows;

public partial class MainWindow : Window
{
    private static readonly SolidColorBrush GreenBrush = new(Color.FromRgb(0x22, 0xC5, 0x5E));
    private static readonly SolidColorBrush CyanBrush = new(Color.FromRgb(0x38, 0xBD, 0xF8));
    private static readonly SolidColorBrush AmberBrush = new(Color.FromRgb(0xF5, 0x9E, 0x0B));
    private static readonly SolidColorBrush SlateBrush = new(Color.FromRgb(0x64, 0x74, 0x8B));
    private static readonly SolidColorBrush ConnectedBadgeBg = new(Color.FromRgb(0x06, 0x3A, 0x28));
    private static readonly SolidColorBrush ConnectedBadgeBorder = new(Color.FromRgb(0x16, 0xA3, 0x4A));
    private static readonly SolidColorBrush IdleBadgeBg = new(Color.FromRgb(0x1E, 0x29, 0x3B));
    private static readonly SolidColorBrush IdleBadgeBorder = new(Color.FromRgb(0x33, 0x41, 0x55));

    private readonly SessionCoordinator _coordinator;
    private readonly List<string> _logLines = new();

    public MainWindow()
    {
        InitializeComponent();

        _coordinator = new SessionCoordinator(AppendLog);
        _coordinator.SessionUpdated += OnSessionUpdated;
        _coordinator.AdbStatusUpdated += OnAdbStatusUpdated;
        _coordinator.BluetoothStatusUpdated += OnBluetoothStatusUpdated;

        Loaded += (_, _) =>
        {
            _coordinator.StartAll();
            RefreshViGemUi();
            RefreshLocalIps();
            OnAdbStatusUpdated(_coordinator.AdbBridge.CurrentStatus);
            OnBluetoothStatusUpdated(_coordinator.BluetoothServer.CurrentStatus);
        };
    }

    private void OnSessionUpdated(ActiveSessionSnapshot snap)
    {
        Dispatcher.BeginInvoke(() =>
        {
            Visualizer.UpdateState(snap.GamepadState);
            TxtRate.Text = $"{snap.PacketsPerSecond} Hz";
            BtnTestRumble.IsEnabled = snap.IsConnected;
            BtnDisconnect.Visibility = snap.IsConnected ? Visibility.Visible : Visibility.Collapsed;

            if (snap.IsConnected)
            {
                ConnectionDot.Fill = GreenBrush;
                ConnectionBadge.Background = ConnectedBadgeBg;
                ConnectionBadge.BorderBrush = ConnectedBadgeBorder;
                ConnectionLabel.Text = $"Connecté · {snap.TransportLabel}";
                ActiveDeviceSubtitle.Text = $"{snap.DeviceName} — Profil : {snap.ProfileName} ({snap.RemoteEndpoint})";
            }
            else
            {
                ConnectionDot.Fill = SlateBrush;
                ConnectionBadge.Background = IdleBadgeBg;
                ConnectionBadge.BorderBrush = IdleBadgeBorder;
                ConnectionLabel.Text = "En attente du téléphone…";
                ActiveDeviceSubtitle.Text = "Connecte ton téléphone en câble USB ou Bluetooth pour jouer";
            }
        });
    }

    private void OnAdbStatusUpdated(AdbStatusSnapshot snap)
    {
        Dispatcher.BeginInvoke(() =>
        {
            TxtAdbStatus.Text = snap.StatusText;
            TxtAdbDetail.Text = snap.DetailText;
            UsbDot.Fill = snap.HasAuthorizedDevice
                ? GreenBrush
                : snap.HasUnauthorizedDevice
                    ? AmberBrush
                    : CyanBrush;
        });
    }

    private void OnBluetoothStatusUpdated(BluetoothStatusSnapshot snap)
    {
        Dispatcher.BeginInvoke(() =>
        {
            TxtBtStatus.Text = snap.StatusText;
            TxtBtDetail.Text = snap.DetailText;
            BtDot.Fill = snap.Listening ? GreenBrush : AmberBrush;
        });
    }

    private void RefreshViGemUi()
    {
        var ok = _coordinator.XboxDriver.IsAvailable;
        ViGemDot.Fill = ok ? GreenBrush : AmberBrush;
        TxtViGemStatus.Text = _coordinator.XboxDriver.StatusMessage;
        ViGemInstallPanel.Visibility = ok ? Visibility.Collapsed : Visibility.Visible;
    }

    private void RefreshLocalIps()
    {
        var endpoints = TcpUsbServer.GetLocalNetworkAddresses();
        if (endpoints.Count == 0)
        {
            TxtLocalIps.Text = "Aucune adresse réseau détectée (branche le câble USB ou active le Wi-Fi).";
            return;
        }

        TxtLocalIps.Text = string.Join(
            Environment.NewLine,
            endpoints.Select(e => $"{e.IpAddress}:{PocketPadConstants.TcpPort}  ({(e.IsUsbTethering ? "USB Partage" : e.InterfaceName)})")
        );
    }

    private void AppendLog(string message)
    {
        var line = $"[{DateTime.Now:HH:mm:ss}] {message}";
        Dispatcher.BeginInvoke(() =>
        {
            _logLines.Add(line);
            if (_logLines.Count > 120)
                _logLines.RemoveAt(0);
            TxtLogs.Text = string.Join(Environment.NewLine, _logLines);
            TxtLogs.ScrollToEnd();
        });
    }

    private async void OnInstallViGemClick(object sender, RoutedEventArgs e)
    {
        BtnInstallViGem.IsEnabled = false;
        try
        {
            var ok = await _coordinator.XboxDriver.InstallViGEmBusAsync(msg =>
                Dispatcher.BeginInvoke(() => TxtViGemProgress.Text = msg));

            RefreshViGemUi();
            if (ok)
            {
                await _coordinator.NotifyServerInfoAsync();
                MessageBox.Show(
                    "Pilote ViGEmBus installé ! Ta manette virtuelle Xbox 360 est maintenant active.",
                    "PocketPad — Prêt à jouer",
                    MessageBoxButton.OK,
                    MessageBoxImage.Information
                );
            }
        }
        finally
        {
            BtnInstallViGem.IsEnabled = true;
        }
    }

    private async void OnRecheckViGemClick(object sender, RoutedEventArgs e)
    {
        _coordinator.XboxDriver.TryInitialize();
        RefreshViGemUi();
        await _coordinator.NotifyServerInfoAsync();
    }

    private async void OnRefreshUsbClick(object sender, RoutedEventArgs e)
    {
        RefreshLocalIps();
        await _coordinator.AdbBridge.ForceRefreshAsync();
    }

    private void OnRestartBluetoothClick(object sender, RoutedEventArgs e)
    {
        _coordinator.BluetoothServer.Restart();
    }

    private void OnRefreshIpsClick(object sender, RoutedEventArgs e)
    {
        RefreshLocalIps();
    }

    private async void OnTestRumbleClick(object sender, RoutedEventArgs e)
    {
        AppendLog("Envoi d’une impulsion de vibration au téléphone…");
        await _coordinator.SendTestRumbleAsync();
    }

    private void OnDisconnectClick(object sender, RoutedEventArgs e)
    {
        _coordinator.DisconnectCurrentClient();
    }

    private async void OnModeChanged(object sender, RoutedEventArgs e)
    {
        if (_coordinator is null) return;
        _coordinator.Mode = RbModeXbox.IsChecked == true
            ? InputRoutingMode.Xbox360Only
            : RbModeKb.IsChecked == true
                ? InputRoutingMode.KeyboardMouseOnly
                : InputRoutingMode.Auto;
        await _coordinator.NotifyServerInfoAsync();
    }

    private void OnClearLogsClick(object sender, RoutedEventArgs e)
    {
        _logLines.Clear();
        TxtLogs.Clear();
    }

    protected override void OnClosing(CancelEventArgs e)
    {
        _coordinator.Dispose();
        base.OnClosing(e);
    }
}
