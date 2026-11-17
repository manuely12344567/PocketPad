using System.Runtime.InteropServices;
using PocketPad.Core.Protocol;

namespace PocketPad.Windows.Services;

/// <summary>
/// Zero-driver Win32 SendInput injector for PC keyboard (using hardware scan codes so DirectX/DirectInput games
/// recognize every key reliably) and relative mouse movement, clicks, and scroll wheel.
/// Guarantees zero stuck keys on disconnect via ReleaseAll().
/// </summary>
public sealed class KeyboardMouseInjector
{
    private readonly object _sync = new();
    private readonly HashSet<ushort> _heldKeys = new();
    private bool _leftMouseHeld;
    private bool _rightMouseHeld;
    private bool _middleMouseHeld;

    public void HandleKeyboard(in KeyboardCommand cmd)
    {
        lock (_sync)
        {
            switch (cmd.Action)
            {
                case KeyboardAction.KeyDown:
                    if (_heldKeys.Add(cmd.VirtualKeyCode))
                        SendKey(cmd.VirtualKeyCode, isKeyUp: false);
                    break;

                case KeyboardAction.KeyUp:
                    _heldKeys.Remove(cmd.VirtualKeyCode);
                    SendKey(cmd.VirtualKeyCode, isKeyUp: true);
                    break;

                case KeyboardAction.Tap:
                    SendKey(cmd.VirtualKeyCode, isKeyUp: false);
                    SendKey(cmd.VirtualKeyCode, isKeyUp: true);
                    _heldKeys.Remove(cmd.VirtualKeyCode);
                    break;
            }
        }
    }

    public void HandleMouse(in MouseCommand cmd)
    {
        lock (_sync)
        {
            if (cmd.DeltaX != 0 || cmd.DeltaY != 0)
            {
                SendMouse(MOUSEEVENTF_MOVE, cmd.DeltaX, cmd.DeltaY, 0);
            }

            if (cmd.WheelDelta != 0)
            {
                SendMouse(MOUSEEVENTF_WHEEL, 0, 0, (uint)cmd.WheelDelta);
            }

            switch (cmd.ButtonAction)
            {
                case MouseButtonAction.LeftDown:
                    if (!_leftMouseHeld)
                    {
                        _leftMouseHeld = true;
                        SendMouse(MOUSEEVENTF_LEFTDOWN, 0, 0, 0);
                    }
                    break;
                case MouseButtonAction.LeftUp:
                    if (_leftMouseHeld)
                    {
                        _leftMouseHeld = false;
                        SendMouse(MOUSEEVENTF_LEFTUP, 0, 0, 0);
                    }
                    break;
                case MouseButtonAction.RightDown:
                    if (!_rightMouseHeld)
                    {
                        _rightMouseHeld = true;
                        SendMouse(MOUSEEVENTF_RIGHTDOWN, 0, 0, 0);
                    }
                    break;
                case MouseButtonAction.RightUp:
                    if (_rightMouseHeld)
                    {
                        _rightMouseHeld = false;
                        SendMouse(MOUSEEVENTF_RIGHTUP, 0, 0, 0);
                    }
                    break;
                case MouseButtonAction.MiddleDown:
                    if (!_middleMouseHeld)
                    {
                        _middleMouseHeld = true;
                        SendMouse(MOUSEEVENTF_MIDDLEDOWN, 0, 0, 0);
                    }
                    break;
                case MouseButtonAction.MiddleUp:
                    if (_middleMouseHeld)
                    {
                        _middleMouseHeld = false;
                        SendMouse(MOUSEEVENTF_MIDDLEUP, 0, 0, 0);
                    }
                    break;
                case MouseButtonAction.LeftClick:
                    SendMouse(MOUSEEVENTF_LEFTDOWN, 0, 0, 0);
                    SendMouse(MOUSEEVENTF_LEFTUP, 0, 0, 0);
                    _leftMouseHeld = false;
                    break;
                case MouseButtonAction.RightClick:
                    SendMouse(MOUSEEVENTF_RIGHTDOWN, 0, 0, 0);
                    SendMouse(MOUSEEVENTF_RIGHTUP, 0, 0, 0);
                    _rightMouseHeld = false;
                    break;
            }
        }
    }

    public void ReleaseAll()
    {
        lock (_sync)
        {
            foreach (var vk in _heldKeys.ToArray())
            {
                SendKey(vk, isKeyUp: true);
            }
            _heldKeys.Clear();

            if (_leftMouseHeld)
            {
                SendMouse(MOUSEEVENTF_LEFTUP, 0, 0, 0);
                _leftMouseHeld = false;
            }
            if (_rightMouseHeld)
            {
                SendMouse(MOUSEEVENTF_RIGHTUP, 0, 0, 0);
                _rightMouseHeld = false;
            }
            if (_middleMouseHeld)
            {
                SendMouse(MOUSEEVENTF_MIDDLEUP, 0, 0, 0);
                _middleMouseHeld = false;
            }
        }
    }

    private static void SendKey(ushort vk, bool isKeyUp)
    {
        uint scan = MapVirtualKeyW(vk, MAPVK_VK_TO_VSC);
        uint flags = KEYEVENTF_SCANCODE;
        if (isKeyUp) flags |= KEYEVENTF_KEYUP;
        if (IsExtendedKey(vk)) flags |= KEYEVENTF_EXTENDEDKEY;

        var input = new INPUT
        {
            type = INPUT_KEYBOARD,
            u = new InputUnion
            {
                ki = new KEYBDINPUT
                {
                    wVk = scan == 0 ? vk : (ushort)0,
                    wScan = (ushort)scan,
                    dwFlags = scan == 0 ? (isKeyUp ? KEYEVENTF_KEYUP : 0u) : flags,
                    time = 0,
                    dwExtraInfo = IntPtr.Zero
                }
            }
        };

        SendInput(1, new[] { input }, Marshal.SizeOf<INPUT>());
    }

    private static void SendMouse(uint flags, int dx, int dy, uint mouseData)
    {
        var input = new INPUT
        {
            type = INPUT_MOUSE,
            u = new InputUnion
            {
                mi = new MOUSEINPUT
                {
                    dx = dx,
                    dy = dy,
                    mouseData = mouseData,
                    dwFlags = flags,
                    time = 0,
                    dwExtraInfo = IntPtr.Zero
                }
            }
        };

        SendInput(1, new[] { input }, Marshal.SizeOf<INPUT>());
    }

    private static bool IsExtendedKey(ushort vk) => vk is
        0x21 or 0x22 or 0x23 or 0x24 or // PageUp/Down, End, Home
        0x25 or 0x26 or 0x27 or 0x28 or // Arrows
        0x2D or 0x2E or                 // Insert, Delete
        0xA3 or 0xA5 or                 // RControl, RAlt
        0x5B or 0x5C;                   // LWin, RWin

    private const uint INPUT_MOUSE = 0;
    private const uint INPUT_KEYBOARD = 1;
    private const uint KEYEVENTF_EXTENDEDKEY = 0x0001;
    private const uint KEYEVENTF_KEYUP = 0x0002;
    private const uint KEYEVENTF_SCANCODE = 0x0008;
    private const uint MAPVK_VK_TO_VSC = 0;

    private const uint MOUSEEVENTF_MOVE = 0x0001;
    private const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    private const uint MOUSEEVENTF_LEFTUP = 0x0004;
    private const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
    private const uint MOUSEEVENTF_RIGHTUP = 0x0010;
    private const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020;
    private const uint MOUSEEVENTF_MIDDLEUP = 0x0040;
    private const uint MOUSEEVENTF_WHEEL = 0x0800;

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

    [DllImport("user32.dll")]
    private static extern uint MapVirtualKeyW(uint uCode, uint uMapType);

    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT
    {
        public uint type;
        public InputUnion u;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion
    {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
        [FieldOffset(0)] public HARDWAREINPUT hi;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT
    {
        public int dx;
        public int dy;
        public uint mouseData;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT
    {
        public ushort wVk;
        public ushort wScan;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct HARDWAREINPUT
    {
        public uint uMsg;
        public ushort wParamL;
        public ushort wParamH;
    }
}
