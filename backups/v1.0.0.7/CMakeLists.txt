cmake_minimum_required(VERSION 3.22.1)
project("tavern-node-bridge")

set(CMAKE_INTERPROCEDURAL_OPTIMIZATION_RELEASE TRUE)
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -Os")

add_library(node-bridge SHARED node-bridge.cpp)

find_library(log-lib log)

target_link_libraries(node-bridge
    ${log-lib}
)
