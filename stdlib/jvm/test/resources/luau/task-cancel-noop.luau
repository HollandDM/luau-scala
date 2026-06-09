local ran = false
local thread = task.spawn(function()
    ran = true
end)

-- thread has already run to completion (spawn is immediate)
-- cancelling it must not error
task.cancel(thread)  -- no-op
task.cancel(nil)     -- nil arg: no-op (no error)
assert(ran == true, "task should have run")
return "cancel-noop ok"
