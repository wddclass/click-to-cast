cmake_minimum_required(VERSION 3.18.1)
project(clicktocast-playfair C)

add_library(playfair SHARED
        playfair_jni.c
        playfair/playfair.c
        playfair/omg_hax.c
        playfair/modified_md5.c
        playfair/sap_hash.c
        playfair/hand_garble.c)

target_include_directories(playfair PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/playfair)
target_link_libraries(playfair m)
