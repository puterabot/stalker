cmake_minimum_required(VERSION 3.10.2)

project(db_dump_custom)

add_executable(db_dump_custom
    src/db_dump.c
    src/db_pr_custom.c
    src/util_cache.c
    src/util_sig.c
)
target_include_directories(db_dump_custom PRIVATE
    src
)
target_link_libraries(db_dump_custom PRIVATE db-5.3)

#set(CMAKE_BUILD_TYPE Debug)
#set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -g")

if(UNIX AND NOT APPLE)
    set(CMAKE_CXX_STANDARD 98)
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}")
    add_definitions(-DLINUX_PLATFORM)
endif()

if(APPLE)
    include_directories(/opt/homebrew/include)
    target_link_directories(db_dump_custom PRIVATE /opt/homebrew/lib)
    add_definitions(-DMACOS_PLATFORM)
endif()

if(CMAKE_BUILD_TYPE MATCHES "Release")
    add_custom_command(
        TARGET db_dump_custom POST_BUILD
        COMMAND strip $<TARGET_FILE:db_dump_custom>
    )
endif()
