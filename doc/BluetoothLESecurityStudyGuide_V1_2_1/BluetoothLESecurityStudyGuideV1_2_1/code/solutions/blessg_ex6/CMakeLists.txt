cmake_minimum_required(VERSION 3.13.1)

include($ENV{ZEPHYR_BASE}/cmake/app/boilerplate.cmake NO_POLICY_SCOPE)
project(pairing_lesecure_numeric_comparison)

zephyr_include_directories_ifdef(CONFIG_BOARD_NRF52840_PCA10056
   $ENV{ZEPHYR_BASE}/boards/arm/nrf52840_pca10056)

FILE(GLOB app_sources src/*.c)
target_sources(app PRIVATE
  ${app_sources}
    src/services/custom_service.c
    src/utils/rssi.c
  )

zephyr_library_include_directories(src)
