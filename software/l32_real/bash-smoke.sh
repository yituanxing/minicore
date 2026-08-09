#!/opt/l32/bash
set -euo pipefail

fib_iter() {
  local n=$1 a=0 b=1 t i
  for ((i=0; i<n; ++i)); do
    t=$((a + b))
    a=$b
    b=$t
  done
  printf '%d' "$a"
}

gcd() {
  local a=$1 b=$2 t
  while (( b != 0 )); do
    t=$(( a % b ))
    a=$b
    b=$t
  done
  printf '%d' "$a"
}

sum=0
for ((i=1; i<=100; ++i)); do
  (( sum += i )) || true
done
[[ $sum -eq 5050 ]]

arr=(alpha beta gamma delta)
[[ ${#arr[@]} -eq 4 ]]
[[ ${arr[2]} == gamma ]]

mapfile -t lines <<< $'three\none\ntwo'
[[ ${#lines[@]} -eq 3 ]]
[[ ${lines[0]} == three && ${lines[2]} == two ]]

f=/tmp/l32-bash-real.txt
printf 'alpha\nbeta\n' > "$f"
printf 'gamma\n' >> "$f"
mapfile -t data < "$f"
[[ ${#data[@]} -eq 3 ]]
[[ ${data[0]} == alpha && ${data[1]} == beta && ${data[2]} == gamma ]]

trap 'signal_seen=42' USR1
signal_seen=0
kill -USR1 $$
[[ $signal_seen -eq 42 ]]

fv=$(fib_iter 20)
gv=$(gcd 462 1071)
[[ $fv -eq 6765 && $gv -eq 21 ]]
printf 'L32_BASH_REAL_PASS %d %d %d\n' "$sum" "$fv" "$gv"
