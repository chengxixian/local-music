# SPDX-License-Identifier: GPL-3.0-or-later
# USB 等时输出的原生库（阶段 2b）。
# 只依赖 NDK 自带的 linux/usbdevice_fs.h 与 log，无需第三方库。
cmake_minimum_required(VERSION 3.22.1)
project(usbisoc C)

add_library(usbisoc SHARED usbisoc.c)

find_library(log_lib log)
target_link_libraries(usbisoc ${log_lib})
