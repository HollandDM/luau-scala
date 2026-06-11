-- ported from lune-org/lune tests/task/spawn.luau (commit: 1f61836)
-- edits:
--   - removed `local task = require("@lune/task")` — task is a global
--   - removed `local fcheck = require("./fcheck")` — inlined helper below
--   - dropped thread-arg variant of task.spawn (task.spawn(thread2)) — contract: first arg must be function
--   - added `__result = "OK"` sentinel at end

local function fcheck(index: number, t: string, value: any)
	if typeof(value) ~= t then
		error(string.format("Expected argument #%d to be of type %s, got %s", index, t, tostring(value)), 2)
	end
end

-- Spawning a task should return the thread that can then be cancelled

local thread = task.spawn(function() end)
assert(type(thread) == "thread", "Spawn should return the thread spawned")

-- Spawned functions should run right away

local flag: boolean = false
task.spawn(function()
	flag = true
end)
assert(flag, "Spawn should run instantly")

-- Spawned functions should work with yielding

local flag2: boolean = false
task.spawn(function()
	task.wait(0.05)
	flag2 = true
end)
assert(not flag2, "Spawn should work with yielding (1)")
task.wait(0.1)
assert(flag2, "Spawn should work with yielding (2)")

-- dropped: task.spawn(thread2) — contract requires first arg to be a function, not a coroutine thread

-- Spawn should be able to be nested

local flag4: boolean = false
task.spawn(function()
	local function nested3()
		task.spawn(function()
			task.wait(0.05)
			flag4 = true
		end)
	end
	local function nested2()
		task.spawn(function()
			task.wait(0.05)
			nested3()
		end)
	end
	local function nested1()
		task.spawn(function()
			task.wait(0.05)
			nested2()
		end)
	end
	task.wait(0.05)
	nested1()
end)
task.wait(0.25)
assert(flag4, "Spawn should work with nesting")

-- Varargs should get passed correctly

local function f(...: any)
	fcheck(1, "string", select(1, ...))
	fcheck(2, "number", select(2, ...))
	fcheck(3, "function", select(3, ...))
end

task.spawn(f, "", 1, f)
task.spawn(f, "inf", math.huge, f)
task.spawn(f, "NaN", 0 / 0, f)

__result = "OK"
