using System.Runtime.InteropServices;
using PocketPad.Core.Protocol;

namespace PocketPad.Windows.Services;

/// <summary>
/// Multiplayer-safe Win32 SendInput injector using hardware scan codes (DirectInput/DirectX compatible).
/// Tracks held keys and mouse buttons per session ID so one player disconnecting never releases keys held by another player!
/// </summary>
public sealed class KeyboardMouseInjector
{
    private readonly object _sync = new();
    private readonly Dictionary<long, HashSet<ushort>> _keysByOwner = new();
    private readonly Dictionary<long, HashSet<int>> _mouseByOwner = new();

    public void HandleKeyboard(long ownerId, in KeyboardCommand cmd)
    {
        lock (_sync)
        {
            var owned = GetOrCreateSet(_keysByOwner, ownerId);
            switch (cmd.Action)
            {
                case KeyboardAction.KeyDown:
                    bool wasHeldAnywhere = IsKeyHeldByAnyone(cmd.VirtualKeyCode);
                    if (owned.Add(cmd.VirtualKeyCode) && !wasHeldAnywhere)
                    {
                        SendKey(cmd.VirtualKeyCode, isKeyUp: false);
                    }
                    break;

                case KeyboardAction.KeyUp:
                    owned.Remove(cmd.VirtualKeyCode);
                    if (!IsKeyHeldByAnyone(cmd.VirtualKeyCode))
                    {
                        SendKey(cmd.VirtualKeyCode, isKeyUp: true);
                    }
                    break;

                case KeyboardAction.Tap:
                    SendKey(cmd.VirtualKeyCode, isKeyUp: false);
                    SendKey(cmd.VirtualKeyCode, isKeyUp: true);
                    owned.Remove(cmd.VirtualKeyCode);
                    break;
            }
        }
    }

    public void HandleMouse(long ownerId, in MouseCommand cmd)
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

            var ownedMouse = GetOrCreateSet(_mouseByOwner, ownerId);
            switch (cmd.ButtonAction)
            {
                case MouseButtonAction.LeftDown:
                    SetMouseButtonLocked(ownedMouse, 1, down: true);
                    break;
                case MouseButtonAction.LeftUp:
                    SetMouseButtonLocked(ownedMouse, 1, down: false);
                    break;
                case MouseButtonAction.RightDown:
                    SetMouseButtonLocked(ownedMouse, 2, down: true);
                    break;
                case MouseButtonAction.RightUp:
                    SetMouseButtonLocked(ownedMouse, 2, down: false);
                    break;
                case MouseButtonAction.MiddleDown:
                    SetMouseButtonLocked(ownedMouse, 3, down: true);
                    break;
                case MouseButtonAction.MiddleUp:
                    SetMouseButtonLocked(ownedMouse, 3, down: false);
                    break;
                case MouseButtonAction.LeftClick:
                    SendMouse(MOUSEEVENTF_LEFTDOWN, 0, 0, 0);
                    SendMouse(MOUSEEVENTF_LEFTUP, 0, 0, 0);
                    ownedMouse.Remove(1);
                    break;
                case MouseButtonAction.RightClick:
                    SendMouse(MOUSEEVENTF_RIGHTDOWN, 0, 0, 0);
                    SendMouse(MOUSEEVENTF_RIGHTUP, 0, 0, 0);
                    ownedMouse.Remove(2);
                    break;
            }
        }
    }

    public void ReleaseOwner(long ownerId)
    {
        lock (_sync)
        {
            if (_keysByOwner.Remove(ownerId, out var keys))
            {
                foreach (var vk in keys)
                {
                    if (!IsKeyHeldByAnyone(vk))
                        SendKey(vk, isKeyUp: true);
                }
            }

            if (_mouseByOwner.Remove(ownerId, out var buttons))
            {
                foreach (var btn in buttons)
                {
                    if (!IsMouseHeldByAnyone(btn))
                        SendMouseButtonRaw(btn, down: false);
                }
            }
        }
    }

    public void ReleaseAll()
    {
        lock (_sync)
        {
            foreach (var owner in _keysByOwner.Keys.ToArray())
            {
                ReleaseOwner(owner);
            }
            foreach (var owner in _mouseByOwner.Keys.ToArray())
            {
                ReleaseOwner(owner);
            }
        }
    }

    private void SetMouseButtonLocked(HashSet<int> ownedMouse, int btn, bool down)
    {
        if (down)
        {
            bool heldAnywhere = IsMouseHeldByAnyone(btn);
            if (ownedMouse.Add(btn) && !heldAnywhere)
                SendMouseButtonRaw(btn, down: true);
        }
        else
        {
            ownedMouse.Remove(btn);
            if (!IsMouseHeldByAnyone(btn))
                SendMouseButtonRaw(btn, down: false);
        }
    }

    private static void SendMouseButtonRaw(int btn, bool down)
    {
        uint flag = btn switch
        {
            1 => down ? MOUSEEVENTF_LEFTDOWN : MOUSEEVENTF_LEFTUP,
            2 => down ? MOUSEEVENTF_RIGHTDOWN : MOUSEEVENTF_RIGHTUP,
            3 => down ? MOUSEEVENTF_MIDDLEDOWN : MOUSEEVENTF_MIDDLEUP,
            _ => 0u
        };
        if (flag != 0u) SendMouse(flag, 0, 0, 0);
    }

    private bool IsKeyHeldByAnyone(ushort vk) => _keysByOwner.Values.Any(s => s.Contains(vk));
    private bool IsMouseHeldByAnyone(int btn) => _mouseByOwner.Values.Any(s => s.Contains(btn));

    private static HashSet<T> GetOrCreateSet<T>(Dictionary<long, HashSet<T>> map, long id)
    {
        if (!map.TryGetValue(id, out var set))
        {
            set = new HashSet<T>();
            map[id] = set;
        }
        return set;
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
        0x21 or 0x22 or 0x23 or 0x24 or
        0x25 or 0x26 or 0x27 or 0x28 or
        0x2D or 0x2E or
        0xA3 or 0xA5 or
        0x5B or 0x5C;

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
