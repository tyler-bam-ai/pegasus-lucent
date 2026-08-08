# SPDX-License-Identifier: CC0-1.0
TARGET = lucent-ppsspp-smoke
OBJS = main.o
CFLAGS = -O2 -G0 -Wall -Wextra
CXXFLAGS = $(CFLAGS) -fno-exceptions -fno-rtti
ASFLAGS = $(CFLAGS)
EXTRA_TARGETS = EBOOT.PBP
PSP_EBOOT_TITLE = Lucent PPSSPP Smoke Fixture

ifndef PSPSDK
$(error PSPSDK must point to a pinned, source-built PSPSDK installation)
endif

include $(PSPSDK)/lib/build.mak
