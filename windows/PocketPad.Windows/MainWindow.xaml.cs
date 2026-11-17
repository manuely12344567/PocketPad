using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Shapes;
using PocketPad.Core.Protocol;
using PocketPad.Windows.Services;

namespace PocketPad.Windows;

public partial class MainWindow : Window
{
    private static readonly SolidColorBrush GreenBrush = new(Color.FromRgb(0x22, 0xC5, 0x5E));
    private static readonly SolidColorBrush CyanBrush = new(Color.FromRgb(0x38, 0xBD, 0xF8));
    private static readonly SolidColorBrush AmberBrush = new(Color.FromRgb(0xF5, 0x9E, 0x0B));
    private static readonly SolidColorBrush SlateDimBrush = new(Color.FromRgb(0x47, 0x55, 0x69));
    private static readonly SolidColorBrush BorderDefaultBrush = new(Color.FromRgb(0x24, 0x31, 0x50));
    private static readonly SolidColorBrush ConnectedBadgeBg = new(Color.FromRgb(0x06, 0x3A, 0x28));
    private static readonly SolidColorBrush ConnectedBadgeBorder = new(Color.FromRgb(0x16, 0xA3, 0x4A));
    private static readonly SolidColorBrush IdleBadgeBg = new(Color.FromRgb(0x1E, 0x29, 0x3B));
    private static readonly SolidColorBrush IdleBadgeBorder = new(Color.FromRgb(0x33, 0x41, 0x55));
    private static readonly SolidColorBrush TextLightBrush = new(Color.FromRgb(0xF1, 0xF5, 0xF9));
    private static readonly SolidColorBrush TextMutedBrush = new(Color.FromRgb(0x94, 0xA3, 0xB8));
    private static readonly SolidColorBrush SlotBgIdle = new(Color.FromRgb(0x11, 0x18, 0x2B));
    private static readonly SolidColorBrush SlotBgSelected = new(Color.FromRgb(0x17, 0x25, 0x44));

    private static readonly SolidColorBrush[] SlotAccentBrushes =
    {
        new(Color.FromRgb(0x38, 0xBD, 0xF8)), // J1 Cyan
        new(Color.FromRgb(0xA8, 0x55, 0xF7)), // J2 Violet
        new(Color.FromRgb(0x22, 0xC5, 0x5E)), // J3 Green
        new(Color.FromRgb(0xF9, 0x73, 0x16)), // J4 Orange
    };

    private readonly SessionCoordinator _coordinator;
    private readonly List<string> _logLines = new();
    private MultiplayerSnapshot? _latestSnapshot;
    private int _selectedSlot = 1;

    public MainWindow()
    {
        InitializeComponent();

        _coordinator = new SessionCoordinator(AppendLog);
        _coordinator.MultiplayerUpdated += OnMultiplayerUpdated;
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

    private void OnMultiplayerUpdated(MultiplayerSnapshot snap)
    {
        Dispatcher.BeginInvoke(() =>
        {
            _latestSnapshot = snap;
            RenderMultiplayer(snap);
        });
    }

    private void RenderMultiplayer(MultiplayerSnapshot snap)
    {
        // Header pill + 4 LED dots
        var leds = new[] { LedP1, LedP2, LedP3, LedP4 };
        for (int i = 0; i < 4; i++)
        {
            var occupied = i < snap.Slots.Count && snap.Slots[i].IsConnected;
            leds[i].Fill = occupied ? SlotAccentBrushes[i] : SlateDimBrush;
        }

        if (snap.ConnectedCount > 0)
        {
            ConnectionBadge.Background = ConnectedBadgeBg;
            ConnectionBadge.BorderBrush = ConnectedBadgeBorder;
            ConnectionLabel.Text = snap.ConnectedCount == 1
                ? "1 / 4 joueur connecté"
                : $"{snap.ConnectedCount} / 4 joueurs connectés";
            BtnDisconnectAll.Visibility = Visibility.Visible;
        }
        else
        {
            ConnectionBadge.Background = IdleBadgeBg;
            ConnectionBadge.BorderBrush = IdleBadgeBorder;
            ConnectionLabel.Text = "0 / 4 joueurs connectés";
            BtnDisconnectAll.Visibility = Visibility.Collapsed;
        }

        // Update 4 slot cards
        var cards = new[] { SlotCard1, SlotCard2, SlotCard3, SlotCard4 };
        var dots = new[] { SlotDot1, SlotDot2, SlotDot3, SlotDot4 };
        var names = new[] { SlotName1, SlotName2, SlotName3, SlotName4 };
        var transports = new[] { SlotTransport1, SlotTransport2, SlotTransport3, SlotTransport4 };
        var hzs = new[] { SlotHz1, SlotHz2, SlotHz3, SlotHz4 };

        for (int i = 0; i < 4 && i < snap.Slots.Count; i++)
        {
            var slotInfo = snap.Slots[i];
            var isSelected = (i + 1) == _selectedSlot;

            cards[i].Background = isSelected ? SlotBgSelected : SlotBgIdle;
            cards[i].BorderBrush = isSelected
                ? SlotAccentBrushes[i]
                : slotInfo.IsConnected
                    ? SlotAccentBrushes[i]
                    : BorderDefaultBrush;
            cards[i].BorderThickness = new Thickness(isSelected ? 2.0 : (slotInfo.IsConnected ? 1.2 : 1.0));

            dots[i].Fill = slotInfo.IsConnected ? GreenBrush : SlateDimBrush;
            names[i].Text = slotInfo.IsConnected ? slotInfo.DeviceName : "Libre";
            names[i].Foreground = slotInfo.IsConnected ? TextLightBrush : TextMutedBrush;
            transports[i].Text = slotInfo.IsConnected ? slotInfo.TransportLabel : "En attente";
            transports[i].Foreground = slotInfo.IsConnected ? SlotAccentBrushes[i] : SlateDimBrush;
            hzs[i].Text = slotInfo.IsConnected ? $"{slotInfo.PacketsPerSecond} Hz" : "—";
            hzs[i].Foreground = slotInfo.IsConnected ? CyanBrush : SlateDimBrush;
        }

        // Update live visualizer for the currently selected player slot
        var focused = snap.Slots.FirstOrDefault(s => s.Slot == _selectedSlot);
        if (focused is not null)
        {
            Visualizer.UpdateState(focused.GamepadState);
            TxtVisualizerTitle.Text = $"VISUALISEUR EN DIRECT · JOUEUR {focused.Slot}";
            TxtVisualizerTitle.Foreground = SlotAccentBrushes[focused.Slot - 1];

            var nextSlot = (focused.Slot % PocketPadConstants.MaxPlayers) + 1;
            BtnSwapSlot.Content = $"J{focused.Slot} ⇄ J{nextSlot}";
            BtnSwapSlot.IsEnabled = focused.IsConnected;
            BtnTestRumble.IsEnabled = focused.IsConnected;
            BtnKickSlot.IsEnabled = focused.IsConnected;

            ActiveDeviceSubtitle.Text = focused.IsConnected
                ? $"{focused.DeviceName} · {focused.TransportLabel} ({focused.RemoteEndpoint}) · Profil : {focused.ProfileName}"
                : $"Emplacement Joueur {focused.Slot} libre — branche un téléphone en USB, Bluetooth ou Wi-Fi !";
        }
    }

    private void SelectSlot(int slot)
    {
        _selectedSlot = slot;
        if (_latestSnapshot is not null)
            RenderMultiplayer(_latestSnapshot);
    }

    private void OnSelectSlot1(object sender, MouseButtonEventArgs e) => SelectSlot(1);
    private void OnSelectSlot2(object sender, MouseButtonEventArgs e) => SelectSlot(2);
    private void OnSelectSlot3(object sender, MouseButtonEventArgs e) => SelectSlot(3);
    private void OnSelectSlot4(object sender, MouseButtonEventArgs e) => SelectSlot(4);

    private async void OnSwapSelectedSlotClick(object sender, RoutedEventArgs e)
    {
        var nextSlot = (_selectedSlot % PocketPadConstants.MaxPlayers) + 1;
        await _coordinator.SwapSlotsAsync(_selectedSlot, nextSlot);
        _selectedSlot = nextSlot;
        if (_latestSnapshot is not null)
            RenderMultiplayer(_latestSnapshot);
    }

    private void OnKickSelectedSlotClick(object sender, RoutedEventArgs e)
    {
        _coordinator.DisconnectSlot(_selectedSlot);
    }

    private void OnDisconnectAllClick(object sender, RoutedEventArgs e)
    {
        _coordinator.DisconnectAll();
    }

    private async void OnTestRumbleClick(object sender, RoutedEventArgs e)
    {
        AppendLog($"Envoi d’une vibration de test au Joueur {_selectedSlot}…");
        await _coordinator.SendTestRumbleAsync(_selectedSlot);
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
            if (_logLines.Count > 150)
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
                _coordinator.EnsureAllActiveXboxSlotsConnected();
                await _coordinator.BroadcastServerInfoAsync();
                MessageBox.Show(
                    "Pilote ViGEmBus installé ! Jusqu’à 4 manettes virtuelles Xbox 360 sont maintenant prêtes pour le multijoueur.",
                    "PocketPad v1.1.0 — Multijoueur prêt",
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
        if (_coordinator.XboxDriver.IsAvailable)
        {
            _coordinator.EnsureAllActiveXboxSlotsConnected();
        }
        RefreshViGemUi();
        await _coordinator.BroadcastServerInfoAsync();
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

    private async void OnModeChanged(object sender, RoutedEventArgs e)
    {
        if (_coordinator is null) return;
        _coordinator.Mode = RbModeXbox.IsChecked == true
            ? InputRoutingMode.Xbox360Only
            : RbModeKb.IsChecked == true
                ? InputRoutingMode.KeyboardMouseOnly
                : InputRoutingMode.Auto;
        await _coordinator.BroadcastServerInfoAsync();
    }

    private void OnKbOwnerToggleChanged(object sender, RoutedEventArgs e)
    {
        if (_coordinator is null) return;
        _coordinator.KeyboardMousePlayer1Only = CbKbPlayer1Only.IsChecked == true;
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
