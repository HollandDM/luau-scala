local ran = false

task.defer(function()
    ran = true
end)

-- Immediately after task.defer returns, the deferred task has NOT started
assert(ran == false, "deferred task must not run before current task yields")

-- Now yield to next tick
task.wait(0)

-- After yielding, the deferred task should have run
assert(ran == true, "deferred task must run after yield")
return "defer ok"
