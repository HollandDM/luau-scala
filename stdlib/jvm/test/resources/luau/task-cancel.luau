local ran = false
local thread = task.delay(10, function()
    ran = true
end)

-- Immediately cancel it
task.cancel(thread)

-- Wait a tick to ensure no stale execution
task.wait(0)

assert(ran == false, "cancelled task must not have run")
return "cancel ok"
