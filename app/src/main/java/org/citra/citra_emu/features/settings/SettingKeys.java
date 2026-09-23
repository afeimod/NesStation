package org.citra.citra_emu.features.settings;

/**
 * Azahar 设置键表 —— 与上游 org.citra.citra_emu.features.settings.SettingKeys
 * （Kotlin object）完全一致：每个方法为 static native String，
 * 返回 config.ini / Settings::values 的键名。
 *
 * Java_org_citra_citra_1emu_features_settings_SettingKeys_* 静态 JNI 导出。
 * NesStation 3DS 设置页 (CoreSettingsPanel/AzaharSettings) 据此构建完整设置项
 * 并写入 <userDir>/config/config.ini，改后调 reloadSettings() 热生效。
 *
 * ★ 该表即 Azahar 2125 支持的全部设置键清单（比上游 UI 暴露的更多）。
 */
public final class SettingKeys {

    private SettingKeys() {
    }

    // ---- Core (CPU/系统) ----
    public static final native String use_cpu_jit();
    public static final native String cpu_clock_percentage();
    public static final native String is_new_3ds();
    public static final native String region_value();
    public static final native String apply_region_free_patch();
    public static final native String init_clock();
    public static final native String init_time();
    public static final native String init_time_offset();
    public static final native String init_ticks_type();
    public static final native String init_ticks_override();
    public static final native String steps_per_hour();
    public static final native String lle_applets();
    public static final native String delay_start_for_lle_modules();
    public static final native String enable_required_online_lle_modules();
    public static final native String allow_plugin_loader();
    public static final native String plugin_loader();
    public static final native String toggle_unique_data_console_type();
    public static final native String use_virtual_sd();
    public static final native String compress_cia_installs();
    public static final native String delay_game_render_thread_us();

    // ---- Renderer (图形) ----
    public static final native String use_gles();
    public static final native String graphics_api();
    public static final native String physical_device();
    public static final native String resolution_factor();
    public static final native String use_vsync();
    public static final native String use_hw_shader();
    public static final native String use_shader_jit();
    public static final native String shaders_accurate_mul();
    public static final native String use_disk_shader_cache();
    public static final native String async_presentation();
    public static final native String async_shader_compilation();
    public static final native String async_custom_loading();
    public static final native String deterministic_async_operations();
    public static final native String spirv_shader_gen();
    public static final native String disable_spirv_optimizer();
    public static final native String disable_right_eye_render();
    public static final native String renderer_debug();
    public static final native String dump_command_buffers();

    // ---- 立体 3D / 后处理 ----
    public static final native String render_3d();
    public static final native String render_3d_which_display();
    public static final native String factor_3d();
    public static final native String mono_render_option();
    public static final native String swap_eyes_3d();
    public static final native String pp_shader_name();
    public static final native String anaglyph_shader_name();
    public static final native String filter_mode();
    public static final native String texture_filter();
    public static final native String texture_sampling();

    // ---- 布局 / 双屏 ----
    public static final native String layout_option();
    public static final native String portrait_layout_option();
    public static final native String secondary_display_layout();
    public static final native String large_screen_proportion();
    public static final native String small_screen_position();
    public static final native String screen_gap();
    public static final native String swap_screen();
    public static final native String upright_screen();
    public static final native String screen_orientation();
    public static final native String expand_to_cutout_area();
    public static final native String custom_top_x();
    public static final native String custom_top_y();
    public static final native String custom_top_width();
    public static final native String custom_top_height();
    public static final native String custom_bottom_x();
    public static final native String custom_bottom_y();
    public static final native String custom_bottom_width();
    public static final native String custom_bottom_height();
    public static final native String custom_second_layer_opacity();
    public static final native String custom_portrait_top_x();
    public static final native String custom_portrait_top_y();
    public static final native String custom_portrait_top_width();
    public static final native String custom_portrait_top_height();
    public static final native String custom_portrait_bottom_x();
    public static final native String custom_portrait_bottom_y();
    public static final native String custom_portrait_bottom_width();
    public static final native String custom_portrait_bottom_height();
    public static final native String screen_top_stretch();
    public static final native String screen_top_leftright_padding();
    public static final native String screen_top_topbottom_padding();
    public static final native String screen_bottom_stretch();
    public static final native String screen_bottom_leftright_padding();
    public static final native String screen_bottom_topbottom_padding();
    public static final native String cardboard_screen_size();
    public static final native String cardboard_x_shift();
    public static final native String cardboard_y_shift();
    public static final native String layouts_to_cycle();

    // ---- 纹理 / 贴图 ----
    public static final native String custom_textures();
    public static final native String preload_textures();
    public static final native String dump_textures();
    public static final native String android_hide_images();

    // ---- 音频 ----
    public static final native String output_type();
    public static final native String output_device();
    public static final native String input_type();
    public static final native String input_device();
    public static final native String audio_emulation();
    public static final native String enable_audio_stretching();
    public static final native String enable_realtime_audio();
    public static final native String volume();
    public static final native String audio_encoder();
    public static final native String audio_encoder_options();
    public static final native String audio_bitrate();
    public static final native String video_encoder();
    public static final native String video_encoder_options();
    public static final native String video_bitrate();

    // ---- 输入 / 体感 ----
    public static final native String touch_device();
    public static final native String motion_device();
    public static final native String udp_input_address();
    public static final native String udp_input_port();
    public static final native String udp_pad_index();
    public static final native String use_artic_base_controller();
    public static final native String last_artic_base_addr();

    // ---- 相机 ----
    public static final native String camera_inner_name();
    public static final native String camera_inner_config();
    public static final native String camera_inner_flip();
    public static final native String camera_outer_left_name();
    public static final native String camera_outer_left_config();
    public static final native String camera_outer_left_flip();
    public static final native String camera_outer_right_name();
    public static final native String camera_outer_right_config();
    public static final native String camera_outer_right_flip();

    // ---- 调试 / 网络 / 性能 ----
    public static final native String use_gdbstub();
    public static final native String gdbstub_port();
    public static final native String log_filter();
    public static final native String log_regex_filter();
    public static final native String instant_debug_log();
    public static final native String enable_rpc_server();
    public static final native String record_frame_times();
    public static final native String use_frame_limit();
    public static final native String frame_limit();
    public static final native String turbo_limit();
    public static final native String use_integer_scaling();

    // ---- 性能 HUD / 模拟帧率 ----
    public static final native String performance_overlay_enable();
    public static final native String performance_overlay_position();
    public static final native String performance_overlay_background();
    public static final native String performance_overlay_show_fps();
    public static final native String performance_overlay_show_frame_time();
    public static final native String performance_overlay_show_speed();
    public static final native String performance_overlay_show_app_ram_usage();
    public static final native String performance_overlay_show_available_ram();
    public static final native String performance_overlay_show_battery_temp();

    // ---- Azahar 新增（play time / citra web / gamemode） ----
    public static final native String use_display_refresh_rate_detection();
    public static final native String enable_gamemode();
    public static final native String citra_username();
    public static final native String citra_token();
    public static final native String web_api_url();

    // ---- 背景色 ----
    public static final native String bg_red();
    public static final native String bg_green();
    public static final native String bg_blue();
}
