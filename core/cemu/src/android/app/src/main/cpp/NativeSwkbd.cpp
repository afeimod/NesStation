#include "JNIUtils.h"

#include "Cafe/OS/libs/swkbd/swkbd.h"

#include <string>

namespace NativeSwkbd
{
        std::atomic<bool> s_isOpen;

        std::string s_currentInputText;

        // ★★★ NesStation R15: 中文（CJK / Unicode）输入支持 ★★★
        //
        // 上游实现按【字节】做差分与注入：
        //   · GetStringDiffs 的 numberOfCharacterToDelete 按 std::string 字节数计数
        //     —— 删除一个中文（UTF-8 3 字节）会注入 3 次 BACKSPACE，游戏侧只
        //     存了 1 个字符 → 多退 2 格，误删后续内容；
        //   · 注入循环 `text.at(i)` 逐【字节】送 keyInput —— 一个中文字符被拆成
        //     3 个 0xE4/0xB8/0xAD 之类的裸字节当“键码”写进 formStringBuffer，
        //     游戏收到的是乱码。
        // 而 swkbd C++ 侧 formStringBuffer 是 wchar_t 数组、GetInputFormString
        // 逐元素转 UTF-16BE 回给游戏（_swapEndianU16），一个码点一个格子，
        // 本来就放得下 CJK —— 差分与注入这两层必须改成【码点】口径：
        //   · 差分 = 公共前缀码点数；删除数 = 旧文本剩余码点数（BACKSPACE 注入
        //     次数与游戏侧字符数严格一致）；
        //   · 注入 = 逐码点 keyInput；> 0xFFFF 的码点（Emoji 等）按 UTF-16 拆成
        //     高/低代理对分两次注入（formStringBuffer 每格存一个 UTF-16 单元，
        //     GetInputFormString 的 16 位转换对代理对天然正确）。
        //   · JNI 传来的 jstring 若含增补平面字符，GetStringUTFChars 是
        //     modified-UTF-8（CESU-8，代理对各占一个 3 字节序列）—— 解码出的
        //     0xD800..0xDFFF 码点按“原样注入”即可，正好等价于代理对注入；
        //     若上层已转标准 UTF-8，则 4 字节序列解码出 > 0xFFFF 码点，走拆对
        //     路径。两条路径殊途同归。

        struct StrDiffs
        {
                size_t newTextStartIndex;         // 新文本中“新增部分”的起始字节偏移
                size_t numberOfCodePointsToDelete; // 需要退格的码点数（= 游戏侧字符数）
        };

        // 解码 s 中字节偏移 i 处的一个 UTF-8 码点，i 前进到下一码点。
        // 非法首字节按 Latin-1 回退（单字节一码点），保证永不失败、永不死循环。
        static uint32 DecodeCodePoint(const std::string& s, size_t& i)
        {
                const unsigned char c = (unsigned char)s[i];
                uint32 cp;
                size_t adv;
                if (c < 0x80)
                {
                        cp = c;
                        adv = 1;
                }
                else if ((c & 0xE0) == 0xC0 && i + 1 < s.size())
                {
                        cp = ((uint32)(c & 0x1F) << 6) | ((uint32)s[i + 1] & 0x3F);
                        adv = 2;
                }
                else if ((c & 0xF0) == 0xE0 && i + 2 < s.size())
                {
                        cp = ((uint32)(c & 0x0F) << 12) | (((uint32)s[i + 1] & 0x3F) << 6) |
                             ((uint32)s[i + 2] & 0x3F);
                        adv = 3;
                }
                else if ((c & 0xF8) == 0xF0 && i + 3 < s.size())
                {
                        cp = ((uint32)(c & 0x07) << 18) | (((uint32)s[i + 1] & 0x3F) << 12) |
                             (((uint32)s[i + 2] & 0x3F) << 6) | ((uint32)s[i + 3] & 0x3F);
                        adv = 4;
                }
                else
                {
                        cp = c; // 非法序列 → Latin-1 单字节回退
                        adv = 1;
                }
                i += adv;
                return cp;
        }

        // 码点级差分：公共前缀（码点数）+ 旧文本剩余码点数。
        // 语义与上游 GetStringDiffs 对齐（前缀口径），但全部按码点计数，
        // 且中间编辑（非纯尾部增删）也按“公共前缀”处理，注入键序为
        // [退格 × 剩余] + [新文本前缀之后的部分]，游戏侧最终文本与
        // 对话框文本一致（游标在游戏侧恒位于末尾，与上游假设相同）。
        static StrDiffs GetStringDiffs(const std::string& newText, const std::string& currentText)
        {
                size_t ni = 0, ci = 0;
                while (ni < newText.size() && ci < currentText.size())
                {
                        const size_t niPrev = ni, ciPrev = ci;
                        const uint32 ncp = DecodeCodePoint(newText, ni);
                        const uint32 ccp = DecodeCodePoint(currentText, ci);
                        if (ncp != ccp)
                        {
                                ni = niPrev;
                                ci = ciPrev;
                                break;
                        }
                }
                StrDiffs diffs;
                diffs.newTextStartIndex = ni;
                size_t remaining = 0;
                while (ci < currentText.size())
                {
                        DecodeCodePoint(currentText, ci);
                        ++remaining;
                }
                diffs.numberOfCodePointsToDelete = remaining;
                return diffs;
        }

        // 向游戏注入一个码点：≤ 0xFFFF 原样注入（含 CESU-8 代理单元场景）；
        // > 0xFFFF（标准 UTF-8 解出的增补平面）拆成 UTF-16 高/低代理对。
        static void InjectCodePoint(uint32 cp)
        {
                if (cp <= 0xFFFF)
                {
                        swkbd::keyInput(cp);
                        return;
                }
                const uint32 v = cp - 0x10000;
                swkbd::keyInput(0xD800u | (v >> 10));
                swkbd::keyInput(0xDC00u | (v & 0x3FFu));
        }

        class AndroidSwkbdCallbacks : public swkbd::swkbdCallbacks
        {
                JNIUtils::Scopedjclass m_emulationActivityClass;
                jmethodID m_showSoftwareKeyboardMethodID;
                jmethodID m_hideSoftwareKeyboardMethodID;

          public:
                AndroidSwkbdCallbacks()
                {
                        JNIEnv* env = JNIUtils::GetEnv();
                        m_emulationActivityClass = JNIUtils::Scopedjclass("info/cemu/cemu/nativeinterface/NativeSwkbd$SwkbdState");
                        m_showSoftwareKeyboardMethodID = env->GetStaticMethodID(*m_emulationActivityClass, "makeVisible", "(Ljava/lang/String;I)V");
                        m_hideSoftwareKeyboardMethodID = env->GetStaticMethodID(*m_emulationActivityClass, "hide", "()V");
                }

                void showSoftwareKeyboard(const std::string& initialText, sint32 maxLength) override
                {
                        s_isOpen = true;

                        s_currentInputText = initialText;

                        JNIUtils::FiberSafeJNICall([&](JNIEnv* env) {
                                jstring j_initialText = JNIUtils::ToJString(env, initialText);
                                JNIUtils::GetEnv()->CallStaticVoidMethod(*m_emulationActivityClass, m_showSoftwareKeyboardMethodID, j_initialText, maxLength);
                                env->DeleteLocalRef(j_initialText);
                        });
                }

                void hideSoftwareKeyboard() override
                {
                        s_isOpen = false;

                        JNIUtils::FiberSafeJNICall([&](JNIEnv* env) {
                                env->CallStaticVoidMethod(*m_emulationActivityClass, m_hideSoftwareKeyboardMethodID);
                        });
                }
        };
} // namespace NativeSwkbd

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeSwkbd_initializeSwkbd([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz)
{
        static std::shared_ptr<swkbd::swkbdCallbacks> swkbdCallbacks = nullptr;

        if (swkbdCallbacks != nullptr)
                return;

        swkbdCallbacks = std::make_shared<NativeSwkbd::AndroidSwkbdCallbacks>();
        swkbd::setSwkbdCallbacks(swkbdCallbacks);
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeSwkbd_onTextChanged([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jstring j_text)
{
        if (!NativeSwkbd::s_isOpen)
                return;

        std::string text = JNIUtils::FromJString(env, j_text);
        const NativeSwkbd::StrDiffs stringDiff =
            NativeSwkbd::GetStringDiffs(text, NativeSwkbd::s_currentInputText);
        for (size_t i = 0; i < stringDiff.numberOfCodePointsToDelete; i++)
                swkbd::keyInput(swkbd::BACKSPACE_KEYCODE);
        for (size_t i = stringDiff.newTextStartIndex; i < text.size();)
                NativeSwkbd::InjectCodePoint(NativeSwkbd::DecodeCodePoint(text, i));
        NativeSwkbd::s_currentInputText = std::move(text);
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeSwkbd_onFinishedInputEdit([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz)
{
        if (!NativeSwkbd::s_isOpen)
                return;

        swkbd::keyInput(swkbd::RETURN_KEYCODE);
}
