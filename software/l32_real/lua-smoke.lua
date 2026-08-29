local function fib(n)
  if n < 2 then return n end
  return fib(n - 1) + fib(n - 2)
end

local function gcd(a, b)
  while b ~= 0 do
    a, b = b, a % b
  end
  return a
end

local sumsq = 0
for i = 1, 20 do
  sumsq = sumsq + i * i
end

local t = {}
for i = 1, 128 do
  t[i] = string.format("v%03d", i)
end
assert(t[1] == "v001" and t[128] == "v128")

local function make_adder(x)
  return function(y) return x + y end
end
assert(make_adder(40)(2) == 42)

local mt = { __add = function(a, b) return setmetatable({v = a.v + b.v}, mt) end }
local a = setmetatable({v = 19}, mt)
local b = setmetatable({v = 23}, mt)
assert((a + b).v == 42)

local ok, err = pcall(function() error("expected") end)
assert(ok == false and string.find(err, "expected", 1, true))

local co = coroutine.create(function()
  coroutine.yield(17)
  return 25
end)
local c1, y = coroutine.resume(co)
local c2, z = coroutine.resume(co)
assert(c1 and y == 17 and c2 and z == 25)

local path = "/tmp/l32-lua-real.txt"
local f = assert(io.open(path, "w"))
f:write("alpha\n", "beta\n")
f:close()
f = assert(io.open(path, "r"))
local data = f:read("*a")
f:close()
assert(data == "alpha\nbeta\n")
os.remove(path)

local fv = fib(20)
local gv = gcd(462, 1071)
assert(fv == 6765 and gv == 21 and sumsq == 2870)
print(string.format("L32_LUA_REAL_PASS %d %d %d", fv, gv, sumsq))
