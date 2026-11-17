using PocketPad.Core.Protocol;

namespace PocketPad.Core.Multiplayer;

/// <summary>
/// Thread-safe slot manager for up to 4 simultaneous players (J1..J4) mixing USB, Bluetooth, and Wi-Fi.
/// Supports lowest-free allocation, reconnect slot preservation by clientId, preferred slot requests, and slot swapping.
/// </summary>
public sealed class PlayerSlotRegistry<TSession> where TSession : class
{
    private readonly object _sync = new();
    private readonly TSession?[] _slots;
    private readonly Dictionary<string, int> _lastSlotByClientId = new(StringComparer.Ordinal);

    public int MaxPlayers { get; }

    public PlayerSlotRegistry(int maxPlayers = PocketPadConstants.MaxPlayers)
    {
        if (maxPlayers is < 1 or > 8)
            throw new ArgumentOutOfRangeException(nameof(maxPlayers));
        MaxPlayers = maxPlayers;
        _slots = new TSession?[maxPlayers];
    }

    public int ConnectedCount
    {
        get
        {
            lock (_sync)
            {
                int c = 0;
                for (int i = 0; i < _slots.Length; i++)
                    if (_slots[i] is not null) c++;
                return c;
            }
        }
    }

    /// <summary>
    /// Allocates a free slot (1..MaxPlayers) for a newly connected session.
    /// Returns 0 if all slots are full.
    /// </summary>
    public int AllocateInitialSlot(TSession session)
    {
        lock (_sync)
        {
            for (int i = 0; i < _slots.Length; i++)
            {
                if (_slots[i] is null)
                {
                    _slots[i] = session;
                    return i + 1;
                }
            }
            return 0;
        }
    }

    /// <summary>
    /// Handles a client's HELLO identity (clientId + optional preferredSlot).
    /// - If another stale session with the exact same clientId is occupying a slot, returns it in evictedSession
    ///   and moves the new session into that slot cleanly.
    /// - If preferredSlot (1..MaxPlayers) is free, moves the session there.
    /// - Otherwise, if clientId previously held a currently-free slot, reclaims it automatically.
    /// </summary>
    public int ApplyIdentity(
        TSession session,
        string clientId,
        int preferredSlot,
        Func<TSession, string> getClientId,
        out TSession? evictedSession)
    {
        lock (_sync)
        {
            evictedSession = null;
            int currentSlot = FindSlotLocked(session);
            if (currentSlot == 0) return 0;

            // 1. If an older session with the exact same clientId is still registered, replace it seamlessly!
            if (!string.IsNullOrWhiteSpace(clientId))
            {
                for (int i = 0; i < _slots.Length; i++)
                {
                    var existing = _slots[i];
                    if (existing is not null && !ReferenceEquals(existing, session) &&
                        string.Equals(getClientId(existing), clientId, StringComparison.Ordinal))
                    {
                        evictedSession = existing;
                        _slots[currentSlot - 1] = null;
                        _slots[i] = session;
                        currentSlot = i + 1;
                        break;
                    }
                }
            }

            // 2. Honor explicit preferred slot (1..MaxPlayers) if free
            if (preferredSlot >= 1 && preferredSlot <= MaxPlayers && preferredSlot != currentSlot)
            {
                if (_slots[preferredSlot - 1] is null)
                {
                    _slots[currentSlot - 1] = null;
                    _slots[preferredSlot - 1] = session;
                    currentSlot = preferredSlot;
                }
            }
            // 3. Otherwise reclaim client's remembered slot if it is currently free
            else if (preferredSlot == 0 && !string.IsNullOrWhiteSpace(clientId) &&
                     _lastSlotByClientId.TryGetValue(clientId, out var remembered) &&
                     remembered >= 1 && remembered <= MaxPlayers &&
                     remembered != currentSlot &&
                     _slots[remembered - 1] is null)
            {
                _slots[currentSlot - 1] = null;
                _slots[remembered - 1] = session;
                currentSlot = remembered;
            }

            if (!string.IsNullOrWhiteSpace(clientId))
            {
                _lastSlotByClientId[clientId] = currentSlot;
            }

            return currentSlot;
        }
    }

    public bool TryMoveOrSwap(int slotA, int slotB, out TSession? sessionAtA, out TSession? sessionAtB)
    {
        lock (_sync)
        {
            sessionAtA = null;
            sessionAtB = null;
            if (slotA < 1 || slotA > MaxPlayers || slotB < 1 || slotB > MaxPlayers || slotA == slotB)
                return false;

            var a = _slots[slotA - 1];
            var b = _slots[slotB - 1];
            if (a is null && b is null) return false;

            _slots[slotA - 1] = b;
            _slots[slotB - 1] = a;
            sessionAtA = b;
            sessionAtB = a;
            return true;
        }
    }

    public int Release(TSession session)
    {
        lock (_sync)
        {
            int slot = FindSlotLocked(session);
            if (slot > 0)
            {
                _slots[slot - 1] = null;
            }
            return slot;
        }
    }

    public TSession? GetAtSlot(int slot)
    {
        lock (_sync)
        {
            return slot >= 1 && slot <= MaxPlayers ? _slots[slot - 1] : null;
        }
    }

    public int GetSlotOf(TSession session)
    {
        lock (_sync)
        {
            return FindSlotLocked(session);
        }
    }

    public IReadOnlyList<(int Slot, TSession Session)> GetAllActive()
    {
        lock (_sync)
        {
            var list = new List<(int, TSession)>(MaxPlayers);
            for (int i = 0; i < _slots.Length; i++)
            {
                if (_slots[i] is { } s)
                    list.Add((i + 1, s));
            }
            return list;
        }
    }

    private int FindSlotLocked(TSession session)
    {
        for (int i = 0; i < _slots.Length; i++)
        {
            if (ReferenceEquals(_slots[i], session))
                return i + 1;
        }
        return 0;
    }
}
