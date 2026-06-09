-- Verify base libraries are accessible
assert(type(math.floor) == "function", "math.floor missing")
assert(type(string.format) == "function", "string.format missing")
assert(type(table.sort) == "function", "table.sort missing")
assert(type(bit32.band) == "function", "bit32.band missing")
assert(type(utf8.len) == "function", "utf8.len missing")
assert(type(os.time) == "function", "os.time missing")
assert(type(coroutine.create) == "function", "coroutine.create missing")
assert(type(buffer.create) == "function", "buffer.create missing")
return "ok"
