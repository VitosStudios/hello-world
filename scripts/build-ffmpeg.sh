#!/usr/bin/env bash
#
# Cross-compile a static FFmpeg binary for one Android ABI with LAME,
# libvorbis and libopus enabled. The resulting binary is placed at
# build/<abi>/libffmpeg.so so it can be dropped into Android's jniLibs/<abi>/
# directory. Naming it lib*.so makes Android's installer extract it to the
# app's nativeLibraryDir with the executable bit set — from there it can be
# invoked via ProcessBuilder even on API 29+.
#
# Required env vars:
#   ANDROID_NDK_HOME     path to NDK r25+
#
# Usage:
#   scripts/build-ffmpeg.sh <abi>
#
#     abi ∈ { arm64-v8a, armeabi-v7a, x86_64 }
set -euo pipefail

ABI="${1:?usage: $0 <abi>}"
API="${API_LEVEL:-24}"

case "$ABI" in
  arm64-v8a)    HOST=aarch64-linux-android;        CLANG=aarch64-linux-android;      FFARCH=aarch64; FFCPU=armv8-a; EXTRA_CFLAGS="" ;;
  armeabi-v7a)  HOST=arm-linux-androideabi;        CLANG=armv7a-linux-androideabi;   FFARCH=arm;     FFCPU=armv7-a; EXTRA_CFLAGS="-mfpu=neon -mfloat-abi=softfp" ;;
  x86_64)       HOST=x86_64-linux-android;         CLANG=x86_64-linux-android;       FFARCH=x86_64;  FFCPU=x86-64;  EXTRA_CFLAGS="" ;;
  *) echo "unsupported abi: $ABI" >&2; exit 2 ;;
esac

NDK="${ANDROID_NDK_HOME:?ANDROID_NDK_HOME is unset}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
export PATH="$TOOLCHAIN/bin:$PATH"

export CC="${CLANG}${API}-clang"
export CXX="${CLANG}${API}-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
export NM="$TOOLCHAIN/bin/llvm-nm"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/.ffmpeg-build/$ABI"
PREFIX="$WORK/prefix"
mkdir -p "$WORK" "$PREFIX"

# Pinned upstream sources -----------------------------------------------------
LAME_VER=3.100
OGG_VER=1.3.5
VORBIS_VER=1.3.7
OPUS_VER=1.5.2
FFMPEG_VER=6.1.2

fetch() {
  local url="$1" out="$2"
  if [ ! -f "$WORK/$out" ]; then
    echo ">>> fetching $url"
    curl -fsSL "$url" -o "$WORK/$out"
  fi
}

extract() {
  local archive="$1" dir="$2"
  if [ ! -d "$WORK/$dir" ]; then
    echo ">>> extracting $archive"
    tar -C "$WORK" -xf "$WORK/$archive"
  fi
}

fetch "https://downloads.sourceforge.net/project/lame/lame/$LAME_VER/lame-$LAME_VER.tar.gz" "lame.tar.gz"
fetch "https://downloads.xiph.org/releases/ogg/libogg-$OGG_VER.tar.gz" "ogg.tar.gz"
fetch "https://downloads.xiph.org/releases/vorbis/libvorbis-$VORBIS_VER.tar.gz" "vorbis.tar.gz"
fetch "https://downloads.xiph.org/releases/opus/opus-$OPUS_VER.tar.gz" "opus.tar.gz"
fetch "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VER.tar.xz" "ffmpeg.tar.xz"

extract "lame.tar.gz"   "lame-$LAME_VER"
extract "ogg.tar.gz"    "libogg-$OGG_VER"
extract "vorbis.tar.gz" "libvorbis-$VORBIS_VER"
extract "opus.tar.gz"   "opus-$OPUS_VER"
extract "ffmpeg.tar.xz" "ffmpeg-$FFMPEG_VER"

COMMON_AUTOCONF=(
  "--host=$HOST"
  "--prefix=$PREFIX"
  "--disable-shared"
  "--enable-static"
)

# --- LAME -------------------------------------------------------------------
if [ ! -f "$PREFIX/lib/libmp3lame.a" ]; then
  echo ">>> building lame"
  pushd "$WORK/lame-$LAME_VER" >/dev/null
  # lame ships a non-portable test that uses xmmintrin.h on x86; the symbols
  # break on aarch64 — disable frontend/decoder which we don't need anyway.
  CFLAGS="$EXTRA_CFLAGS" ./configure \
    "${COMMON_AUTOCONF[@]}" \
    --disable-frontend --disable-decoder
  make -j"$(nproc)"
  make install
  popd >/dev/null
fi

# --- libogg -----------------------------------------------------------------
if [ ! -f "$PREFIX/lib/libogg.a" ]; then
  echo ">>> building libogg"
  pushd "$WORK/libogg-$OGG_VER" >/dev/null
  CFLAGS="$EXTRA_CFLAGS" ./configure "${COMMON_AUTOCONF[@]}"
  make -j"$(nproc)"
  make install
  popd >/dev/null
fi

# --- libvorbis --------------------------------------------------------------
if [ ! -f "$PREFIX/lib/libvorbis.a" ]; then
  echo ">>> building libvorbis"
  pushd "$WORK/libvorbis-$VORBIS_VER" >/dev/null
  CFLAGS="$EXTRA_CFLAGS" ./configure \
    "${COMMON_AUTOCONF[@]}" \
    --disable-examples --disable-docs --disable-oggtest \
    --with-ogg="$PREFIX"
  make -j"$(nproc)"
  make install
  popd >/dev/null
fi

# --- opus -------------------------------------------------------------------
if [ ! -f "$PREFIX/lib/libopus.a" ]; then
  echo ">>> building opus"
  pushd "$WORK/opus-$OPUS_VER" >/dev/null
  CFLAGS="$EXTRA_CFLAGS" ./configure \
    "${COMMON_AUTOCONF[@]}" \
    --disable-doc --disable-extra-programs
  make -j"$(nproc)"
  make install
  popd >/dev/null
fi

# --- FFmpeg -----------------------------------------------------------------
echo ">>> configuring ffmpeg"
pushd "$WORK/ffmpeg-$FFMPEG_VER" >/dev/null

# Force pkg-config to look only at our static prefix.
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"

./configure \
  --prefix="$PREFIX" \
  --pkg-config-flags="--static" \
  --target-os=android \
  --arch="$FFARCH" \
  --cpu="$FFCPU" \
  --cc="$CC" \
  --cxx="$CXX" \
  --ar="$AR" \
  --nm="$NM" \
  --ranlib="$RANLIB" \
  --strip="$STRIP" \
  --sysroot="$TOOLCHAIN/sysroot" \
  --enable-cross-compile \
  --disable-shared \
  --enable-static \
  --enable-pic \
  --disable-doc \
  --disable-debug \
  --disable-ffplay \
  --disable-ffprobe \
  --disable-symver \
  --disable-network \
  --enable-libmp3lame \
  --enable-libvorbis \
  --enable-libopus \
  --extra-cflags="-I$PREFIX/include -fPIE $EXTRA_CFLAGS" \
  --extra-ldflags="-L$PREFIX/lib -pie" \
  --extra-libs="-lm"

make -j"$(nproc)"

OUT="$ROOT/build/$ABI"
mkdir -p "$OUT"
"$STRIP" -o "$OUT/libffmpeg.so" ./ffmpeg
ls -lah "$OUT/libffmpeg.so"
file "$OUT/libffmpeg.so" || true
popd >/dev/null

echo ">>> done: $OUT/libffmpeg.so"
