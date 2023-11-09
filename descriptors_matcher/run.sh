#!/bin/bash

# Number of CPU threads
N=72

run_avx2() {
    local I="$1"
    # Note this is assuming a NUMA system with 2 compute nodes. Should change on different topology.
    if ((I % 2 == 0)); then
        CPU_NODE=0
    else
        CPU_NODE=1
    fi
    numactl --cpunodebind=$CPU_NODE --membind=$CPU_NODE ./build/avx2 $N $I > "log_$(printf "%02d" $I).txt"
}

for ((I=0; I<N; I++)); do
    run_avx2 "$I" &
done

wait
