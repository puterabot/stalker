#!/bin/bash

N=72

run_avx2() {
    local I="$1"
    ./build/avx2 $N $I > "log_$(printf "%02d" $I).txt"
}

for ((I=0; I<N; I++)); do
    run_avx2 "$I" &
done

wait
