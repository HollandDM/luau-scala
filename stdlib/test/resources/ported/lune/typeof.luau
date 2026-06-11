-- ported from lune-org/lune tests/globals/typeof.luau (commit: 1f61836)
-- edits:
--   - removed `--!nolint UnknownType` — not needed without roblox types
--   - removed `local task = require("@lune/task")` — task is a global
--   - dropped roblox section (lines 21-29 upstream) — requires @lune/roblox, no equivalent lib
--   - added `__result = "OK"` sentinel at end

-- dropped: local roblox = require("@lune/roblox") and all roblox.Instance/Vector3/Enum asserts — no roblox lib available

local function f() end

local thread1 = coroutine.create(f)
local thread2 = task.spawn(f)
local thread3 = task.defer(f)
local thread4 = task.delay(0, f)

assert(
	typeof(thread1) == "thread",
	"Calling typeof() did not return 'thread' after coroutine.create"
)
assert(typeof(thread2) == "thread", "Calling typeof() did not return 'thread' after task.spawn")
assert(typeof(thread3) == "thread", "Calling typeof() did not return 'thread' after task.defer")
assert(typeof(thread4) == "thread", "Calling typeof() did not return 'thread' after delay")

__result = "OK"
