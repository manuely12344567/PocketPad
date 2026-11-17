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
/// Manages up to 4 simultaneous Virtual Xbox 360 Controllers (XInput Slots 1..4) via a single Nefarius ViGEmClient.
/// Dynamically plugs in each virtual controller when a player joins a slot and unplugs it cleanly when they leave,
/// so multiplayer PC games (FIFA, Rocket League, Mortal Kombat, Overcooked, etc.) always see the exact player count.
/// Routes per-controller game rumble independently back to each player's phone.
/// </summary>
public sealed class VirtualXboxDriver : IDisposable
{
    public const string ViGemInstallerUrl =
        "https://github.com/nefarius/ViGEmBus/releases/download/v1.22.0/ViGEmBus_1.22.0_x64_x86_arm64.exe";

    private readonly Action<string> _log;
    private readonly object _sync = new();
    private readonly Dictionary<int, IXbox360Controller> _controllers = new();
    private readonly Dictionary<int, Xbox360FeedbackReceivedEventHandler> _feedbackHandlers = new();

    private ViGEmClient? _client;

    public bool IsAvailable { get; private set; }
    public string StatusMessage { get; private set; } = "Vérification du pilote ViGEmBus…";

    public event Action<int, byte, byte>? RumbleReceived;

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

                // Quick probe to verify ViGEmBus kernel driver is actually loaded and responsive
                var probe = _client.CreateXbox360Controller();
                probe.Connect();
                probe.Disconnect();

                IsAvailable = true;
                StatusMessage = "Multijoueur Xbox 360 prêt (jusqu’à 4 manettes virtuelles ViGEmBus)";
                _log("Pilote ViGEmBus actif — prêt pour 1 à 4 manettes virtuelles Xbox 360 simultanées !");
                return true;
            }
            catch (VigemBusNotFoundException)
            {
                CleanupInternal();
                IsAvailable = false;
                StatusMessage = "Pilote ViGEmBus non installé (mode Clavier/Souris déjà actif)";
                _log("ViGEmBus absent — clique sur « Installer le pilote Xbox (1 clic) » pour activer les 4 manettes Xbox 360.");
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

    public void EnsureSlotConnected(int slot)
    {
        if (slot < 1 || slot > PocketPadConstants.MaxPlayers) return;
        lock (_sync)
        {
            if (!IsAvailable || _client is null) return;
            if (_controllers.ContainsKey(slot)) return;

            try
            {
                var ctrl = _client.CreateXbox360Controller();
                ctrl.AutoSubmitReport = false;
                Xbox360FeedbackReceivedEventHandler handler = (_, e) =>
                    RumbleReceived?.Invoke(slot, e.LargeMotor, e.SmallMotor);

                ctrl.FeedbackReceived += handler;
                ctrl.Connect();

                _controllers[slot] = ctrl;
                _feedbackHandlers[slot] = handler;
                _log($"Manette virtuelle Xbox 360 branchée pour Joueur {slot}");
            }
            catch (Exception ex)
            {
                _log($"Impossible de créer la manette Xbox J{slot} : {ex.Message}");
            }
        }
    }

    public void DisconnectSlot(int slot)
    {
        lock (_sync)
        {
            if (!_controllers.TryGetValue(slot, out var ctrl)) return;
            try
            {
                ApplyStateLocked(ctrl, GamepadState.Neutral);
                if (_feedbackHandlers.TryGetValue(slot, out var handler))
                {
                    ctrl.FeedbackReceived -= handler;
                }
                ctrl.Disconnect();
            }
            catch
            {
                // Ignore teardown glitches
            }
            finally
            {
                _controllers.Remove(slot);
                _feedbackHandlers.Remove(slot);
                _log($"Manette virtuelle Xbox 360 débranchée pour Joueur {slot}");
            }
        }
    }

    public void ApplyState(int slot, in GamepadState state)
    {
        lock (_sync)
        {
            if (!IsAvailable) return;
            if (!_controllers.TryGetValue(slot, out var ctrl)) return;

            try
            {
                ApplyStateLocked(ctrl, state);
            }
            catch (Exception ex)
            {
                _log($"Erreur envoi état Xbox J{slot} : {ex.Message}");
            }
        }
    }

    public void ResetSlotToNeutral(int slot)
    {
        ApplyState(slot, GamepadState.Neutral);
    }

    private static void ApplyStateLocked(IXbox360Controller ctrl, in GamepadState state)
    {
        ctrl.SetButtonState(Xbox360Button.Up, state.HasButton(GamepadButtons.DpadUp));
        ctrl.SetButtonState(Xbox360Button.Down, state.HasButton(GamepadButtons.DpadDown));
        ctrl.SetButtonState(Xbox360Button.Left, state.HasButton(GamepadButtons.DpadLeft));
        ctrl.SetButtonState(Xbox360Button.Right, state.HasButton(GamepadButtons.DpadRight));
        ctrl.SetButtonState(Xbox360Button.Start, state.HasButton(GamepadButtons.Start));
        ctrl.SetButtonState(Xbox360Button.Back, state.HasButton(GamepadButtons.Back));
        ctrl.SetButtonState(Xbox360Button.LeftThumb, state.HasButton(GamepadButtons.LeftThumb));
        ctrl.SetButtonState(Xbox360Button.RightThumb, state.HasButton(GamepadButtons.RightThumb));
        ctrl.SetButtonState(Xbox360Button.LeftShoulder, state.HasButton(GamepadButtons.LeftShoulder));
        ctrl.SetButtonState(Xbox360Button.RightShoulder, state.HasButton(GamepadButtons.RightShoulder));
        ctrl.SetButtonState(Xbox360Button.Guide, state.HasButton(GamepadButtons.Guide));
        ctrl.SetButtonState(Xbox360Button.A, state.HasButton(GamepadButtons.A));
        ctrl.SetButtonState(Xbox360Button.B, state.HasButton(GamepadButtons.B));
        ctrl.SetButtonState(Xbox360Button.X, state.HasButton(GamepadButtons.X));
        ctrl.SetButtonState(Xbox360Button.Y, state.HasButton(GamepadButtons.Y));

        ctrl.SetSliderValue(Xbox360Slider.LeftTrigger, state.LeftTrigger);
        ctrl.SetSliderValue(Xbox360Slider.RightTrigger, state.RightTrigger);

        ctrl.SetAxisValue(Xbox360Axis.LeftThumbX, state.LeftStickX);
        ctrl.SetAxisValue(Xbox360Axis.LeftThumbY, state.LeftStickY);
        ctrl.SetAxisValue(Xbox360Axis.RightThumbX, state.RightStickX);
        ctrl.SetAxisValue(Xbox360Axis.RightThumbY, state.RightStickY);

        ctrl.SubmitReport();
    }

    public async Task<bool> InstallViGEmBusAsync(Action<string> reportProgress)
    {
        var tempFile = Path.Combine(Path.GetTempPath(), "ViGEmBus_1.22.0_x64_x86_arm64.exe");
        try
        {
            reportProgress("Téléchargement officiel de ViGEmBus v1.22.0…");
            using var http = new HttpClient { Timeout = TimeSpan.FromMinutes(2) };
            http.DefaultRequestHeaders.UserAgent.ParseAdd("PocketPad/1.1.0");

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

            reportProgress("Activation des manettes virtuelles Xbox 360…");
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
        foreach (var kv in _controllers.ToArray())
        {
            try
            {
                if (_feedbackHandlers.TryGetValue(kv.Key, out var handler))
                    kv.Value.FeedbackReceived -= handler;
                kv.Value.Disconnect();
            }
            catch { }
        }
        _controllers.Clear();
        _feedbackHandlers.Clear();

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
