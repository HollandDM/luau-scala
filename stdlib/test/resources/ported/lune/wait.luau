-- ported from lune-org/lune tests/task/wait.luau (commit: 1f61836)
-- edits:
--   - removed `local process = require("@lune/process")` — no process lib
--   - removed `local stdio = require("@lune/stdio")` — no stdio lib
--   - removed `local task = require("@lune/task")` — task is a global
--   - hardcoded EPSILON = 50/1000 (replaces process.os-conditional; covers JVM scheduling jitter)
--   - replaced `stdio.format(returned)` with `tostring(returned)`
--   - replaced `if process.os == "windows" then 15/1000 else 5/1000` with hardcoded `5/1000`
--   - TEST_ACCURACY stays false — upper-bound accuracy block is dead code (kept for structure)
--   - added sentinel at end

local TEST_ACCURACY = false

local EPSILON = 50 / 1_000

local function test(expected: number)
	local start = os.clock()
	local returned = task.wait(expected)
	if typeof(returned) ~= "number" then
		error(
			string.format(
				"Expected task.wait to return a number, got %s %s",
				typeof(returned),
				tostring(returned)
			),
			2
		)
	end
	local elapsed = os.clock() - start
	if elapsed < expected then
		error(
			string.format(
				"Expected task.wait to yield for at least %.3f seconds, yielded for %.3f seconds",
				expected,
				elapsed
			)
		)
	end
	if not TEST_ACCURACY then
		return
	end
	local difference = math.abs(elapsed - expected)
	if difference > EPSILON then
		error(
			string.format(
				"Elapsed time diverged too much from argument!"
					.. "\nGot argument of %.3fms and elapsed time of %.3fms"
					.. "\nGot maximum difference of %.3fms and real difference of %.3fms",
				expected * 1_000,
				elapsed * 1_000,
				EPSILON * 1_000,
				difference * 1_000
			)
		)
	end
end

local function measure(duration: number)
	for _ = 1, 5 do
		test(duration)
	end
end

-- About 5ms minimum safe sleep; hardcoded (no process.os check needed)
measure(5 / 1_000)

measure(1 / 60)
measure(1 / 30)
measure(1 / 20)
measure(1 / 10)

-- Wait should work in other threads

local flag: boolean = false
task.spawn(function()
	task.wait(0.1)
	flag = true
end)
assert(not flag, "Wait failed while inside task-spawned thread (1)")
task.wait(0.2)
assert(flag, "Wait failed while inside task-spawned thread (2)")

-- Non-finite, negative, and out-of-range arguments should be handled
-- gracefully and not crash the process - see issue #346

-- dropped from the bad-input list: math.huge, 1e30 — luau-scala does not
-- clamp oversized delays (Roblox parity: they park until cancel/deadline),
-- so a forever-wait is unobservable here
for _, bad in { 0 / 0, -1, -0.5, -math.huge } do
	local returned = task.wait(bad)
	assert(
		typeof(returned) == "number",
		`task.wait should return a number for out-of-range input, got {typeof(returned)}`
	)
end

return "OK"
