/* Copyright  (C) 2010-2020 The RetroArch team
 *
 * ---------------------------------------------------------------------------------------
 * The following license statement only applies to this file (retro_environment.h).
 * ---------------------------------------------------------------------------------------
 *
 * Permission is hereby granted, free of charge,
 * to any person obtaining a copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software,
 * and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

#ifndef __LIBRETRO_SDK_ENVIRONMENT_H
#define __LIBRETRO_SDK_ENVIRONMENT_H

#if defined (__cplusplus)
#elif defined(__STDC__)
#if (__STDC__ == 1)
#define __STDC_ISO__
#else
#endif
#if defined(__STDC_VERSION__)
#if (__STDC_VERSION__ >= 201112L)
#define __STDC_C11__
#elif (__STDC_VERSION__ >= 199901L)
#define __STDC_C99__
#elif (__STDC_VERSION__ >= 199409L)
#define __STDC_C89__
#define __STDC_C89_AMENDMENT_1__
#else
#define __STDC_C89__
#endif
#else
#define __STDC_C89__
#endif
#else
#endif

#if defined(WIN32) || defined(_WIN32) || defined(__CYGWIN__) || defined(__MINGW32__)
#if defined(_MSC_VER) && defined(__has_include)
#if __has_include(<winapifamily.h>)
#define HAVE_WINAPIFAMILY_H 1
#else
#define HAVE_WINAPIFAMILY_H 0
#endif
#elif defined(_MSC_VER) && (_MSC_VER >= 1700 && !_USING_V110_SDK71_)
#define HAVE_WINAPIFAMILY_H 1
#else
#define HAVE_WINAPIFAMILY_H 0
#endif
#if HAVE_WINAPIFAMILY_H
#include <winapifamily.h>
#define WINAPI_FAMILY_WINRT (!WINAPI_FAMILY_PARTITION(WINAPI_PARTITION_DESKTOP) && WINAPI_FAMILY_PARTITION(WINAPI_PARTITION_APP))
#else
#define WINAPI_FAMILY_WINRT 0
#endif
#if WINAPI_FAMILY_WINRT
#undef __WINRT__
#define __WINRT__ 1
#endif
#if defined(_M_IX86_FP) && (_M_IX86_FP == 1)
#define __SSE__ 1
#elif (defined(_M_IX86_FP) && (_M_IX86_FP == 2)) || (defined(_M_AMD64) || defined(_M_X64))
#define __SSE__ 1
#define __SSE2__ 1
#endif
#endif

#endif