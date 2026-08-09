#!/opt/l32/bash
set -euo pipefail

fib() {
  local n=$1
  if (( n < 2 )); then
    printf '%d' "$n"
  else
    printf '%d' "$(( $(fib $((n-1))) + $(fib $((n-2))) ))"
  fi
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

mapfile -t lines < <(printf 'three\none\ntwo\n')
[[ ${#lines[@]} -eq 3 ]]
[[ ${lines[0]} == three && ${lines[2]} == two ]]

f=/tmp/l32-bash-real.txt
printf 'alpha\nbeta\n' > "$f"
printf 'gamma\n' >> "$f"
mapfile -t data < "$f"
[[ ${#data[@]} -eq 3 ]]
[[ ${data[0]} == alpha && ${data[1]} == beta && ${data[2]} == gamma ]]
rm -f "$f" 2>/dev/null || :

trap 'signal_seen=42' USR1
signal_seen=0
kill -USR1 $$
[[ $signal_seen -eq 42 ]]

fv=$(fib 10)
gv=$(gcd 462 1071)
[[ $fv -eq 55 && $gv -eq 21 ]]
printf 'L32_BASH_REAL_PASS %d %d %d\n' "$sum" "$fv" "$gv"
