local t0 = os.clock()
local elapsed = task.wait(0.05)
local t1 = os.clock()

assert(elapsed >= 0.05,
    string.format("elapsed %f < 0.05", elapsed))

local wall = t1 - t0
assert(wall >= 0.05,
    string.format("wall clock %f < 0.05", wall))

assert(elapsed < 2.05,
    string.format("elapsed %f unexpectedly large", elapsed))
return "timing ok"
