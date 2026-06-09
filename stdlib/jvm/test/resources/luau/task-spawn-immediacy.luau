local ran = false

-- spawn returns before f finishes if f yields, but f starts immediately
task.spawn(function()
    ran = true
end)

-- After task.spawn returns, `ran` must be true because spawn resumes immediately
assert(ran == true, "spawned task must have run before spawn returned")
return "immediacy ok"
