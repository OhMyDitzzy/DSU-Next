#![allow(clippy::too_many_arguments)]

use std::ffi::c_void;
use std::fs::{self, File};
use std::io::{self, BufReader, BufWriter, Write};
use std::path::{Path, PathBuf};
use std::ptr;

use yuki_core::fs::Segments;
use yuki_core::{avb, br, build, dat, extract, lp, payload, rom, sdat};
use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jint, jlong, jobjectArray, jstring};
use jni::{JNIEnv, NativeMethod};

use crate::util::{
    guard, logger, msg, opt_string, opt_strings, pending, string, string_array, Res,
};

const BRIDGE_CLASS: &str = "com/ditzzy/dsunext/yuki/NativeBridge";

const S: &str = "Ljava/lang/String;";
const A: &str = "[Ljava/lang/String;";
const L: &str = "Lcom/ditzzy/dsunext/yuki/YukiLogger;";

pub fn register(env: &mut JNIEnv) -> jni::errors::Result<()> {
    let table: Vec<(&str, String, *mut c_void)> = vec![
        (
            "yukiVersion",
            format!("(){S}"),
            yuki_version as *mut c_void,
        ),
        (
            "initWorkDir",
            format!("({S}){A}"),
            init_work_dir as *mut c_void,
        ),
        ("unpack", format!("({S}{S}{L})V"), unpack as *mut c_void),
        ("state", format!("({S}){A}"), state as *mut c_void),
        ("romInfo", format!("({S}){A}"), rom_info as *mut c_void),
        (
            "repack",
            format!("({S}{S}II{S}{S}I{S}{S}{L}){A}"),
            repack as *mut c_void,
        ),
        ("cleanup", format!("({S}Z){A}"), cleanup as *mut c_void),
        (
            "extractImage",
            format!("({S}{S}{S}{L}){A}"),
            extract_image as *mut c_void,
        ),
        (
            "buildImage",
            format!("({S}{S}{S}{S}Z{L}){A}"),
            build_image as *mut c_void,
        ),
        (
            "addHashtreeFooter",
            format!("({S}{S}{S}{L}){A}"),
            add_hashtree_footer as *mut c_void,
        ),
        (
            "payloadInfo",
            format!("({S}){A}"),
            payload_info as *mut c_void,
        ),
        (
            "payloadDump",
            format!("({S}{S}{A}I{L}){A}"),
            payload_dump as *mut c_void,
        ),
        (
            "superInfo",
            format!("({S}I){A}"),
            super_info as *mut c_void,
        ),
        (
            "superDump",
            format!("({S}{S}{A}I{L}){A}"),
            super_dump as *mut c_void,
        ),
        (
            "sdat2img",
            format!("({S}{S}{S}{L}){S}"),
            sdat2img as *mut c_void,
        ),
        (
            "img2sdat",
            format!("({S}{S}{S}II{L}){A}"),
            img2sdat as *mut c_void,
        ),
        (
            "brotliCompress",
            format!("({S}{S}II)J"),
            brotli_compress as *mut c_void,
        ),
        (
            "brotliDecompress",
            format!("({S}{S})J"),
            brotli_decompress as *mut c_void,
        ),
    ];
    let methods: Vec<NativeMethod> = table
        .into_iter()
        .map(|(name, sig, fn_ptr)| NativeMethod {
            name: name.into(),
            sig: sig.into(),
            fn_ptr,
        })
        .collect();
    env.register_native_methods(BRIDGE_CLASS, &methods)
}

fn paths(list: &[PathBuf]) -> Vec<String> {
    list.iter().map(|p| p.display().to_string()).collect()
}

fn format_name(format: rom::Format) -> &'static str {
    match format {
        rom::Format::Sdat => "sdat",
        rom::Format::Fastboot => "fastboot",
        rom::Format::Payload => "payload",
        rom::Format::Super => "super",
    }
}

fn select<'a, T>(
    names: &Option<Vec<String>>,
    all: impl FnOnce() -> Vec<&'a T>,
    find: impl Fn(&str) -> Option<&'a T>,
    what: &str,
) -> Res<Vec<&'a T>> {
    match names {
        None => Ok(all()),
        Some(names) => names
            .iter()
            .map(|n| find(n.as_str()).ok_or_else(|| format!("no partition {n} in the {what}")))
            .collect(),
    }
}

extern "system" fn yuki_version<'l>(mut env: JNIEnv<'l>, _class: JClass<'l>) -> jstring {
    guard(&mut env, ptr::null_mut(), |env| {
        env.new_string(env!("CARGO_PKG_VERSION"))
            .map(|s| s.into_raw())
            .map_err(msg)
    })
}

extern "system" fn init_work_dir<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    work: JString<'l>,
) -> jobjectArray {
    guard(&mut env, ptr::null_mut(), |env| {
        let work = string(env, &work)?;
        let made = rom::init(Path::new(&work)).map_err(msg)?;
        string_array(env, &paths(&made))
    })
}

extern "system" fn unpack<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    input: JString<'l>,
    work: JString<'l>,
    log: JObject<'l>,
) {
    guard(&mut env, (), |env| {
        let input = string(env, &input)?;
        let work = string(env, &work)?;
        fs::create_dir_all(&work).map_err(msg)?;
        {
            let mut log = logger(env, &log);
            rom::unpack(Path::new(&input), Path::new(&work), &mut log).map_err(msg)?;
        }
        pending(env)
    })
}

/// [format, (name, transfer list version, brotli 0/1, size, fs)*] or null
/// when the folder is not unpacked.
extern "system" fn state<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    work: JString<'l>,
) -> jobjectArray {
    guard(&mut env, ptr::null_mut(), |env| {
        let work = string(env, &work)?;
        let Some(state) = rom::read_full_state(Path::new(&work)).map_err(msg)? else {
            return Ok(ptr::null_mut());
        };
        let mut out = vec![format_name(state.format).to_string()];
        for p in &state.partitions {
            out.extend([
                p.name.clone(),
                p.version.to_string(),
                (p.brotli as u8).to_string(),
                p.size.to_string(),
                p.fs.clone(),
            ]);
        }
        string_array(env, &out)
    })
}

/// [key, value]* in the order yuki reports them.
extern "system" fn rom_info<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    work: JString<'l>,
) -> jobjectArray {
    guard(&mut env, ptr::null_mut(), |env| {
        let work = string(env, &work)?;
        let out: Vec<String> = rom::rom_info(Path::new(&work))
            .into_iter()
            .flat_map(|(k, v)| [k, v])
            .collect();
        string_array(env, &out)
    })
}

/// Negative numbers and null strings keep what `yuki.prop` says.
extern "system" fn repack<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    work: JString<'l>,
    output: JObject<'l>,
    brotli: jint,
    zip: jint,
    formats: JObject<'l>,
    payload_output: JObject<'l>,
    xz: jint,
    sign_key: JObject<'l>,
    sign_cert: JObject<'l>,
    log: JObject<'l>,
) -> jobjectArray {
    guard(&mut env, ptr::null_mut(), |env| {
        let work = PathBuf::from(string(env, &work)?);
        let output = opt_string(env, output)?.map(PathBuf::from);
        let mut opts = rom::load_config(&work).map_err(msg)?;
        if brotli >= 0 {
            opts.brotli_quality = brotli as u32;
        }
        if zip >= 0 {
            opts.zip_level = zip as i64;
        }
        if xz >= 0 {
            opts.xz_level = xz as u32;
        }
        if let Some(list) = opt_string(env, formats)? {
            opts.output = rom::Target::parse_list(&list)
                .ok_or_else(|| format!("bad output formats: {list}"))?;
        }
        if let Some(kind) = opt_string(env, payload_output)? {
            opts.payload_output = rom::PayloadOutput::parse(&kind)
                .ok_or_else(|| format!("bad payload output: {kind}"))?;
        }
        if let Some(key) = opt_string(env, sign_key)? {
            opts.sign_key = Some(PathBuf::from(key));
        }
        if let Some(cert) = opt_string(env, sign_cert)? {
            opts.sign_cert = Some(PathBuf::from(cert));
        }
        let zips = {
            let mut log = logger(env, &log);
            rom::repack(&work, output.as_deref(), opts, &mut log).map_err(msg)?
        };
        pending(env)?;
        string_array(env, &paths(&zips))
    })
}

extern "system" fn cleanup<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    work: JString<'l>,
    all: jboolean,
) -> jobjectArray {
    guard(&mut env, ptr::null_mut(), |env| {
        let work = string(env, &work)?;
        let removed = rom::cleanup(Path::new(&work), all != 0).map_err(msg)?;
        string_array(env, &paths(&removed))
    })
}

/// [fs type, partition, mount point, dirs, files, symlinks,
/// symlinks not created, special, bytes, warning*]
extern "system" fn extract_image<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    image: JString<'l>,
    out_dir: JString<'l>,
    part: JObject<'l>,
    log: JObject<'l>,
) -> jobjectArray {
    guard(&mut env, ptr::null_mut(), |env| {
        let image = string(env, &image)?;
        let out_dir = string(env, &out_dir)?;
        let part = opt_string(env, part)?;
        let sum = {
            let mut log = logger(env, &log);
            extract::extract(
                Path::new(&image),
                Path::new(&out_dir),
                part.as_deref(),
                &mut log,
            )
            .map_err(msg)?
        };
        pending(env)?;
        let mut out = vec![
            sum.fs_type,
            sum.part,
            sum.mount_point,
            sum.dirs.to_string(),
            sum.files.to_string(),
            sum.symlinks.to_string(),
            sum.symlinks_not_created.to_string(),
            sum.special.to_string(),
            sum.bytes.to_string(),
        ];
        out.extend(sum.warnings);
        string_array(env, &out)
    })
}

/// [original size, hashed size, tree offset, tree size, vbmeta offset,
/// vbmeta size, final size, root digest (hex), salt (hex)]
extern "system" fn add_hashtree_footer<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    image: JString<'l>,
    part: JString<'l>,
    algorithm: JObject<'l>,
    log: JObject<'l>,
) -> jobjectArray {
    guard(&mut env, ptr::null_mut(), |env| {
        let image = string(env, &image)?;
        let part = string(env, &part)?;
        let mut opts = avb::HashtreeOptions::new(part);
        if let Some(name) = opt_string(env, algorithm)? {
            opts.hash_algorithm = name.parse::<avb::HashAlgorithm>()?;
        }
        let footer = {
            let mut log = logger(env, &log);
            avb::add_hashtree_footer(Path::new(&image), &opts, &mut log).map_err(msg)?
        };
        pending(env)?;
        let hex = |b: &[u8]| b.iter().map(|x| format!("{x:02x}")).collect::<String>();
        let out = vec![
            footer.original_size.to_string(),
            footer.image_size.to_string(),
            footer.tree_offset.to_string(),
            footer.tree_size.to_string(),
            footer.vbmeta_offset.to_string(),
            footer.vbmeta_size.to_string(),
            footer.final_size.to_string(),
            hex(&footer.root_digest),
            hex(&footer.salt),
        ];
        string_array(env, &out)
    })
}

/// [mount point, dirs, files, symlinks, removed, block size, new entry count,
/// new entry*, warning*]
extern "system" fn build_image<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    work: JString<'l>,
    part: JString<'l>,
    output: JString<'l>,
    size: JObject<'l>,
    force: jboolean,
    log: JObject<'l>,
) -> jobjectArray {
    guard(&mut env, ptr::null_mut(), |env| {
        let work = string(env, &work)?;
        let part = string(env, &part)?;
        let output = string(env, &output)?;
        let size = match opt_string(env, size)? {
            Some(text) => text.parse::<build::Size>()?,
            None => build::Size::Original,
        };
        if force == 0 && Path::new(&output).exists() {
            return Err(format!("{output} already exists"));
        }
        let sum = {
            let mut log = logger(env, &log);
            build::build(
                Path::new(&work),
                &part,
                Path::new(&output),
                size,
                &mut log,
            )
            .map_err(msg)?
        };
        pending(env)?;
        let mut out = vec![
            sum.mount_point,
            sum.dirs.to_string(),
            sum.files.to_string(),
            sum.symlinks.to_string(),
            sum.removed.to_string(),
            sum.block_size.to_string(),
            sum.new_entries.len().to_string(),
        ];
        out.extend(sum.new_entries);
        out.extend(sum.warnings);
        string_array(env, &out)
    })
}

/// [block size, full OTA 0/1, partition count, (name, size, operations)*,
/// (group, max size, comma separated partitions)*]
extern "system" fn payload_info<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    path: JString<'l>,
) -> jobjectArray {
    guard(&mut env, ptr::null_mut(), |env| {
        let path = string(env, &path)?;
        let (_reader, info) = payload::open(Path::new(&path)).map_err(msg)?;
        let m = &info.manifest;
        let mut out = vec![
            m.block_size.to_string(),
            (m.check_full().is_ok() as u8).to_string(),
            m.partitions.len().to_string(),
        ];
        for p in &m.partitions {
            out.extend([p.name.clone(), p.size.to_string(), p.ops.len().to_string()]);
        }
        for g in &m.groups {
            out.extend([g.name.clone(), g.max_size.to_string(), g.partitions.join(",")]);
        }
        string_array(env, &out)
    })
}

extern "system" fn payload_dump<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    path: JString<'l>,
    out_dir: JString<'l>,
    names: JObject<'l>,
    threads: jint,
    log: JObject<'l>,
) -> jobjectArray {
    guard(&mut env, ptr::null_mut(), |env| {
        let path = string(env, &path)?;
        let out_dir = PathBuf::from(string(env, &out_dir)?);
        let names = opt_strings(env, names)?;
        let threads = if threads > 0 {
            threads as usize
        } else {
            payload::default_threads()
        };
        let (mut reader, info) = payload::open(Path::new(&path)).map_err(msg)?;
        let m = &info.manifest;
        m.check_full().map_err(msg)?;
        let parts = select(
            &names,
            || m.partitions.iter().collect(),
            |n| m.partition(n),
            "payload",
        )?;
        fs::create_dir_all(&out_dir).map_err(|e| format!("{}: {e}", out_dir.display()))?;
        let mut written = Vec::new();
        {
            let mut log = logger(env, &log);
            for p in parts {
                let img = out_dir.join(format!("{}.img", p.name));
                log(&format!("- {}: {} MiB -> {}", p.name, p.size >> 20, img.display()));
                let mut file = File::options()
                    .read(true)
                    .write(true)
                    .create(true)
                    .truncate(true)
                    .open(&img)
                    .map_err(|e| format!("{}: {e}", img.display()))?;
                payload::dump_partition(&mut reader, &info, p, &mut file, threads)
                    .map_err(msg)?;
                written.push(img);
            }
        }
        pending(env)?;
        string_array(env, &paths(&written))
    })
}

fn open_super(path: &str) -> Res<lp::SuperReader<BufReader<File>>> {
    let file = File::open(path).map_err(|e| format!("{path}: {e}"))?;
    lp::SuperReader::open(BufReader::new(file)).map_err(msg)
}

fn slot_number(slot: jint) -> Res<u32> {
    u32::try_from(slot).map_err(|_| format!("bad slot: {slot}"))
}

/// [super size, slot count, sparse 0/1, group count, (group, max size)*,
/// (partition, size, group)*]
extern "system" fn super_info<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    path: JString<'l>,
    slot: jint,
) -> jobjectArray {
    guard(&mut env, ptr::null_mut(), |env| {
        let path = string(env, &path)?;
        let mut img = open_super(&path)?;
        let m = lp::read_super(&mut img, slot_number(slot)?).map_err(msg)?;
        let mut out = vec![
            m.super_size().to_string(),
            m.geometry.slot_count.to_string(),
            (img.is_sparse() as u8).to_string(),
            m.groups.len().to_string(),
        ];
        for g in &m.groups {
            out.extend([g.name.clone(), g.max_size.to_string()]);
        }
        for p in &m.partitions {
            out.extend([
                p.name.clone(),
                p.size().to_string(),
                m.groups[p.group as usize].name.clone(),
            ]);
        }
        string_array(env, &out)
    })
}

extern "system" fn super_dump<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    path: JString<'l>,
    out_dir: JString<'l>,
    names: JObject<'l>,
    slot: jint,
    log: JObject<'l>,
) -> jobjectArray {
    guard(&mut env, ptr::null_mut(), |env| {
        let path = string(env, &path)?;
        let out_dir = PathBuf::from(string(env, &out_dir)?);
        let names = opt_strings(env, names)?;
        let mut img = open_super(&path)?;
        let m = lp::read_super(&mut img, slot_number(slot)?).map_err(msg)?;
        let parts = select(
            &names,
            || m.partitions.iter().filter(|p| p.size() > 0).collect(),
            |n| m.partition(n),
            "super image",
        )?;
        fs::create_dir_all(&out_dir).map_err(|e| format!("{}: {e}", out_dir.display()))?;
        let mut written = Vec::new();
        {
            let mut log = logger(env, &log);
            for p in parts {
                let target = out_dir.join(format!("{}.img", p.name));
                log(&format!(
                    "- {}: {} MiB -> {}",
                    p.name,
                    p.size() >> 20,
                    target.display()
                ));
                let segments = m.segments(p).map_err(msg)?;
                let mut src = Segments::new(&mut img, &segments);
                let mut dst = BufWriter::new(
                    File::create(&target).map_err(|e| format!("{}: {e}", target.display()))?,
                );
                io::copy(&mut src, &mut dst).map_err(msg)?;
                dst.flush().map_err(msg)?;
                written.push(target);
            }
        }
        pending(env)?;
        string_array(env, &paths(&written))
    })
}

extern "system" fn sdat2img<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    transfer_list: JString<'l>,
    new_data: JString<'l>,
    output: JString<'l>,
    log: JObject<'l>,
) -> jstring {
    guard(&mut env, ptr::null_mut(), |env| {
        let transfer_list = string(env, &transfer_list)?;
        let new_data = string(env, &new_data)?;
        let output = string(env, &output)?;
        let list = {
            let mut log = logger(env, &log);
            sdat::dat_to_img(&transfer_list, &new_data, &output, &mut log).map_err(msg)?
        };
        pending(env)?;
        env.new_string(sdat::android_version_name(list.version))
            .map(|s| s.into_raw())
            .map_err(msg)
    })
}

/// [written blocks, new blocks]
extern "system" fn img2sdat<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    image: JString<'l>,
    out_dir: JString<'l>,
    prefix: JString<'l>,
    version: jint,
    brotli: jint,
    log: JObject<'l>,
) -> jobjectArray {
    guard(&mut env, ptr::null_mut(), |env| {
        let image = string(env, &image)?;
        let out_dir = string(env, &out_dir)?;
        let prefix = string(env, &prefix)?;
        let version = u32::try_from(version).map_err(|_| format!("bad version: {version}"))?;
        let brotli = u32::try_from(brotli).ok();
        let plan = {
            let mut log = logger(env, &log);
            dat::img_to_dat(&image, &out_dir, &prefix, version, brotli, &mut log)
                .map_err(msg)?
        };
        pending(env)?;
        let out = vec![
            plan.written_blocks().to_string(),
            plan.new_blocks().to_string(),
        ];
        string_array(env, &out)
    })
}

/// A negative quality or window picks the yuki default.
extern "system" fn brotli_compress<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    input: JString<'l>,
    output: JString<'l>,
    quality: jint,
    window: jint,
) -> jlong {
    guard(&mut env, 0, |env| {
        let input = string(env, &input)?;
        let output = string(env, &output)?;
        let quality = u32::try_from(quality).unwrap_or(br::DEFAULT_QUALITY);
        let window = u32::try_from(window).unwrap_or(br::DEFAULT_LGWIN);
        br::compress_file(Path::new(&input), Path::new(&output), quality, window)
            .map(|n| n as jlong)
            .map_err(msg)
    })
}

extern "system" fn brotli_decompress<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    input: JString<'l>,
    output: JString<'l>,
) -> jlong {
    guard(&mut env, 0, |env| {
        let input = string(env, &input)?;
        let output = string(env, &output)?;
        br::decompress_file(Path::new(&input), Path::new(&output))
            .map(|n| n as jlong)
            .map_err(msg)
    })
}
