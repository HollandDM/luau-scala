-- io must not exist
assert(io == nil, "io should be nil")
-- os.execute must not exist
assert(os.execute == nil, "os.execute should be nil")
assert(os.exit == nil, "os.exit should be nil")
assert(os.getenv == nil, "os.getenv should be nil")
-- package must not exist
assert(package == nil, "package should be nil")
-- dofile / loadfile must not exist
assert(dofile == nil, "dofile should be nil")
assert(loadfile == nil, "loadfile should be nil")
return "sandbox ok"
