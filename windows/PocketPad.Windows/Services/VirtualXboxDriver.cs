using System.Diagnostics;
using System.IO;
using System.Net.Http;
using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Exceptions;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.Xbox360;
using PocketPad.Core.Protocol;

namespace PocketPad.Windows.Services;

/// <summary>
/// Manages the Virtual Xbox 360 Controller (XInput) via Nefarius ViGEmBus,
/// forwards real PC game rumble back to the phone, and provides a 1-click official ViGEmBus installer.
/// </summary>
public sealed class VirtualXboxDriver : IDisposable
{
    public const string ViGemInstallerUrl =
        "https://github.com/nefarius/ViGEmBus/releases/download/v1.22.0/ViGEmBus_1.22.0_x64_x86_arm64.exe";

    private readonly Action<string> _log;
    private readonly object _sync = new();

    private ViGEmClient? _client;
    private IXbox360Controller? _controller;

    public bool IsAvailable { get; private set; }
    public string StatusMessage { get; private set; } = "Vérification du pilote ViGEmBus…";

    public event Action<byte, byte>? RumbleReceived;

    public VirtualXboxDriver(Action<string> log)
    {
        _log = log;
    }

    public bool TryInitialize()
    {
        lock (_sync)
        {
            CleanupInternal();
            try
            {
                _client = new ViGEmClient();
                _controller = _client.CreateXbox360Controller();
                _controller.AutoSubmitReport = false;
                _controller.FeedbackReceived += OnFeedbackReceived;
                _controller.Connect();

                IsAvailable = true;
                StatusMessage = "Manette Xbox 360 virtuelle active (ViGEmBus prêt)";
                _log("Manette virtuelle Xbox 360 connectée (ViGEmBus OK)");
                return true;
            }
            catch (VigemBusNotFoundException)
            {
                CleanupInternal();
                IsAvailable = false;
                StatusMessage = "Pilote ViGEmBus non installé (mode Clavier/Souris déjà actif)";
                _log("ViGEmBus absent — clique sur « Installer le pilote Xbox (1 clic) » pour activer la manette Xbox 360.");
                return false;
            }
            catch (Exception ex)
            {
                CleanupInternal();
                IsAvailable = false;
                StatusMessage = $"Pilote Xbox indisponible : {ex.Message}";
                _log($"Erreur initialisation ViGEmBus : {ex.Message}");
                return false;
            }
        }
    }

    public void ApplyState(in GamepadState state)
    {
        lock (_sync)
        {
            if (!IsAvailable || _controller is null) return;

            try
            {
                _controller.SetButtonState(Xbox360Button.Up, state.HasButton(GamepadButtons.DpadUp));
                _controller.SetButtonState(Xbox360Button.Down, state.HasButton(GamepadButtons.DpadDown));
                _controller.SetButtonState(Xbox360Button.Left, state.HasButton(GamepadButtons.DpadLeft));
                _controller.SetButtonState(Xbox360Button.Right, state.HasButton(GamepadButtons.DpadRight));
                _controller.SetButtonState(Xbox360Button.Start, state.HasButton(GamepadButtons.Start));
                _controller.SetButtonState(Xbox360Button.Back, state.HasButton(GamepadButtons.Back));
                _controller.SetButtonState(Xbox360Button.LeftThumb, state.HasButton(GamepadButtons.LeftThumb));
                _controller.SetButtonState(Xbox360Button.RightThumb, state.HasButton(GamepadButtons.RightThumb));
                _controller.SetButtonState(Xbox360Button.LeftShoulder, state.HasButton(GamepadButtons.LeftShoulder));
                _controller.SetButtonState(Xbox360Button.RightShoulder, state.HasButton(GamepadButtons.RightShoulder));
                _controller.SetButtonState(Xbox360Button.Guide, state.HasButton(GamepadButtons.Guide));
                _controller.SetButtonState(Xbox360Button.A, state.HasButton(GamepadButtons.A));
                _controller.SetButtonState(Xbox360Button.B, state.HasButton(GamepadButtons.B));
                _controller.SetButtonState(Xbox360Button.X, state.HasButton(GamepadButtons.X));
                _controller.SetButtonState(Xbox360Button.Y, state.HasButton(GamepadButtons.Y));

                _controller.SetSliderValue(Xbox360Slider.LeftTrigger, state.LeftTrigger);
                _controller.SetSliderValue(Xbox360Slider.RightTrigger, state.RightTrigger);

                _controller.SetAxisValue(Xbox360Axis.LeftThumbX, state.LeftStickX);
                _controller.SetAxisValue(Xbox360Axis.LeftThumbY, state.LeftStickY);
                _controller.SetAxisValue(Xbox360Axis.RightThumbX, state.RightStickX);
                _controller.SetAxisValue(Xbox360Axis.RightThumbY, state.RightStickY);

                _controller.SubmitReport();
            }
            catch (Exception ex)
            {
                _log($"Erreur envoi état Xbox 360 : {ex.Message}");
            }
        }
    }

    public void ResetToNeutral()
    {
        ApplyState(GamepadState.Neutral);
    }

    private void OnFeedbackReceived(object sender, Xbox360FeedbackReceivedEventArgs e)
    {
        RumbleReceived?.Invoke(e.LargeMotor, e.SmallMotor);
    }

    public async Task<bool> InstallViGEmBusAsync(Action<string> reportProgress)
    {
        var tempFile = Path.Combine(Path.GetTempPath(), "ViGEmBus_1.22.0_x64_x86_arm64.exe");
        try
        {
            reportProgress("Téléchargement officiel de ViGEmBus v1.22.0…");
            using var http = new HttpClient { Timeout = TimeSpan.FromMinutes(2) };
            http.DefaultRequestHeaders.UserAgent.ParseAdd("PocketPad/1.0.0");

            using var resp = await http.GetAsync(ViGemInstallerUrl, HttpCompletionOption.ResponseHeadersRead);
            resp.EnsureSuccessStatusCode();

            await using (var fs = new FileStream(tempFile, FileMode.Create, FileAccess.Write, FileShare.None))
            {
                await resp.Content.CopyToAsync(fs);
            }

            reportProgress("Lancement de l’installateur ViGEmBus (accepte l’invite Windows)…");
            var psi = new ProcessStartInfo
            {
                FileName = tempFile,
                UseShellExecute = true,
                Verb = "runas",
            };

            using var proc = Process.Start(psi);
            if (proc is not null)
            {
                await proc.WaitForExitAsync();
            }

            reportProgress("Activation de la manette virtuelle Xbox 360…");
            await Task.Delay(800);
            return TryInitialize();
        }
        catch (Exception ex)
        {
            _log($"Installation ViGEmBus interrompue : {ex.Message}");
            reportProgress($"Installation annulée ou échouée : {ex.Message}");
            return false;
        }
    }

    private void CleanupInternal()
    {
        if (_controller is not null)
        {
            try { _controller.FeedbackReceived -= OnFeedbackReceived; } catch { }
            try { _controller.Disconnect(); } catch { }
            _controller = null;
        }
        if (_client is not null)
        {
            try { _client.Dispose(); } catch { }
            _client = null;
        }
    }

    public void Dispose()
    {
        lock (_sync)
        {
            CleanupInternal();
            IsAvailable = false;
        }
    }
}
