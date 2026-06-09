local b = buffer.create(8)
assert(buffer.len(b) == 8)
buffer.writei32(b, 0, 0xDEADBEEF)
local v = buffer.readu32(b, 0)
assert(v == 0xDEADBEEF, string.format("got %x", v))
buffer.fill(b, 0, 0)
assert(buffer.readu32(b, 0) == 0)
return "buffer ok"
