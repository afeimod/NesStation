package com.nesstation.app.core.engine

/**
 * FBNeo 改版 ROM .dif 差分条目表（自动生成，勿手工编辑）。
 *
 * 来源：上游 FBNeo src/burn/drv 驱动源码中所有包含 *.dif 差分条目的
 * romset（生成脚本 scripts/extract_dif_table.py）。共 15 个 romset、
 * 72 条差分。核心以 CRC 逐一校验（与打包在 libfbneo_libretro_android.so
 * 内的驱动定义一致），缺失即报 "ROM with name xxx.dif and CRC 0x... is missing"
 * 并拒绝启动 —— 见 libretro.cpp open_archive() 审计逻辑。
 */
internal object FbNeoDifTable {

    /** 一条 .dif 差分文件期望：文件名 / 精确字节数 / 期望 CRC32 */
    class DifEntry(val name: String, val size: Int, val crc: Long)

    val table: Map<String, List<DifEntry>> = mapOf(
        "dinofw" to listOf(
            DifEntry("cdfw_23a.dif", 0x80000, 0xCD06B4B2L),
            DifEntry("cdfw_22a.dif", 0x80000, 0x95C05099L),
            DifEntry("cdfw_21a.dif", 0x80000, 0x60DBAAAEL),
        ),
        "ffightdw" to listOf(
            DifEntry("ffdw_36a.dif", 0x20000, 0xAA162ECEL),
            DifEntry("ffdw_42a.dif", 0x20000, 0xCABB8E1AL),
            DifEntry("ffdw_37a.dif", 0x20000, 0xD71746ADL),
            DifEntry("ffdw_43a.dif", 0x20000, 0x23E41BEAL),
        ),
        "kf2k23rd" to listOf(
            DifEntry("265-p13rd.dif", 0x100000, 0xB69FC632L),
            DifEntry("265-p23rd.dif", 0x400000, 0x9DB1D92AL),
            DifEntry("265-c13rd.dif", 0x800000, 0x1073E68CL),
            DifEntry("265-c23rd.dif", 0x800000, 0x9AFB5756L),
        ),
        "kof2kotc" to listOf(
            DifEntry("257-p1otc.dif", 0x100000, 0x6E51E168L),
            DifEntry("257-p2otc.dif", 0x400000, 0xBA4D48D2L),
            DifEntry("257-c1otc.dif", 0x800000, 0x51081B95L),
            DifEntry("257-c2otc.dif", 0x800000, 0x9C76D30EL),
            DifEntry("257-c3otc.dif", 0x800000, 0xABB6B9B1L),
            DifEntry("257-c4otc.dif", 0x800000, 0x99316D6AL),
            DifEntry("257-c7otc.dif", 0x800000, 0x903E277DL),
            DifEntry("257-c8otc.dif", 0x800000, 0xF8470466L),
        ),
        "kof96ae" to listOf(
            DifEntry("214-p1ae.dif", 0x100000, 0x27460E16L),
            DifEntry("214-p2ae.dif", 0x400000, 0xB5300D86L),
            DifEntry("214-s1ae.dif", 0x20000, 0xE7B193F6L),
            DifEntry("214-c5ae.dif", 0x400000, 0x3E9F5D11L),
            DifEntry("214-c6ae.dif", 0x400000, 0x6E8BA626L),
            DifEntry("214-c9ae.dif", 0x400000, 0xFC6801B1L),
            DifEntry("214-c10ae.dif", 0x400000, 0xDFF8E33DL),
            DifEntry("214-c11ae.dif", 0x400000, 0xB89CFD0DL),
            DifEntry("214-c12ae.dif", 0x400000, 0x215F2AB5L),
            DifEntry("214-m1ae.dif", 0x20000, 0x8D7FB2A4L),
            DifEntry("214-v3ae.dif", 0x400000, 0xFD28107DL),
        ),
        "kof97ae" to listOf(
            DifEntry("232-p1aef.dif", 0x100000, 0x8DA71759L),
            DifEntry("232-p2aef.dif", 0x400000, 0xC3C641B6L),
        ),
        "kof97t" to listOf(
            DifEntry("232-p1t.dif", 0x100000, 0xB162219AL),
            DifEntry("232-p2t.dif", 0x400000, 0xA5E6D358L),
            DifEntry("232-s1t.dif", 0x20000, 0x18C4F447L),
            DifEntry("232-c1t.dif", 0x800000, 0x29A75F37L),
            DifEntry("232-c2t.dif", 0x800000, 0x5F5506F4L),
            DifEntry("232-c5t.dif", 0x800000, 0x30ABF231L),
            DifEntry("232-c6t.dif", 0x800000, 0x67D28823L),
        ),
        "kof98ae" to listOf(
            DifEntry("242-p1ae.dif", 0x100000, 0x520E2B8BL),
            DifEntry("242-p2ae.dif", 0x600000, 0xD926BF9L),
            DifEntry("242-s1ae.dif", 0x20000, 0x3DD00989L),
            DifEntry("242-c1ae.dif", 0x800000, 0x11A5B3ECL),
            DifEntry("242-c2ae.dif", 0x800000, 0x595472F3L),
            DifEntry("242-c11ae.dif", 0x800000, 0xA492FAF1L),
            DifEntry("242-c12ae.dif", 0x800000, 0x137CF580L),
        ),
        "kof98cb" to listOf(
            DifEntry("242-p1cb.dif", 0x100000, 0xEE8EC128L),
            DifEntry("242-s1cb.dif", 0x20000, 0x83ECD0F4L),
        ),
        "kof98mix" to listOf(
            DifEntry("242-p1mix.dif", 0x100000, 0x620068E3L),
            DifEntry("242-p2mix.dif", 0x400000, 0x173AFFB5L),
            DifEntry("242-s1mix.dif", 0x20000, 0xE3BC013BL),
            DifEntry("242-c9mix.dif", 0x800000, 0x811E05F7L),
            DifEntry("242-c10mix.dif", 0x800000, 0xB2FA7031L),
        ),
        "kof98pfe" to listOf(
            DifEntry("242-p2pfe.dif", 0x400000, 0x635B6ED1L),
        ),
        "kof99eur" to listOf(
            DifEntry("152-p1eur.dif", 0x100000, 0x7C726033L),
            DifEntry("152-p2eur.dif", 0x400000, 0xAED40004L),
            DifEntry("251-s1eur.dif", 0x20000, 0x6B074249L),
            DifEntry("251-c3eur.dif", 0x800000, 0x589C2C78L),
            DifEntry("251-c4eur.dif", 0x800000, 0x25E5E66BL),
            DifEntry("251-c5eur.dif", 0x800000, 0x55EEA175L),
            DifEntry("251-c6eur.dif", 0x800000, 0x741924A5L),
        ),
        "kof99t" to listOf(
            DifEntry("152-p1t.dif", 0x100000, 0x819DBAEBL),
            DifEntry("152-p2t.dif", 0x400000, 0xC16E8D5CL),
        ),
        "oldsplusnr" to listOf(
            DifEntry("v-208cn.dif", 0x400000, 0x56EF7C1BL),
            DifEntry("igs_t05301w064.dif", 0x800000, 0x27B8BE4BL),
            DifEntry("igs_a05301nw064.dif", 0x800000, 0x28A6F9F3L),
            DifEntry("igs_a05303w064.dif", 0x800000, 0x56E00D62L),
            DifEntry("igs_a05304w064.dif", 0x800000, 0x560F9B68L),
            DifEntry("igs_b05301nw064.dif", 0x800000, 0x1565554BL),
            DifEntry("igs_b05302nw064.dif", 0x800000, 0x86924573L),
            DifEntry("igs_w05301b032.dif", 0x400000, 0x20EB7BC6L),
        ),
        "sengk3eb" to listOf(
            DifEntry("261-p1eb.dif", 0x100000, 0xA285CE0DL),
        ),
    )
}
