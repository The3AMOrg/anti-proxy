cmake_minimum_required(VERSION 3.13.1)

include($ENV{ZEPHYR_BASE}/cmake/app/boilerplate.cmake NO_POLICY_SCOPE)
project(pairing_just_works)

FILE(GLOB app_sources src/*.c)
target_sources(app PRIVATE
  ${app_sources}
  src/services/custom_service.c
  )

zephyr_library_include_directories(src)
