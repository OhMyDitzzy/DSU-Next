use std::fs::{File, OpenOptions};
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::path::Path;
use std::str::FromStr;

use sha1::Sha1;
use sha2::{Digest, Sha256, Sha512};

use crate::fs::invalid;

/// Data and hash block size (avbtool's default `--block_size`).
const BLOCK_SIZE: u64 = 4096;

const FOOTER_MAGIC: &[u8; 4] = b"AVBf";
const FOOTER_SIZE: usize = 64;

const VBMETA_MAGIC: &[u8; 4] = b"AVB0";
const VBMETA_HEADER_SIZE: usize = 256;
/// Size of the release string field in the vbmeta header, NUL included.
const RELEASE_STRING_SIZE: usize = 48;
/// Same string avbtool writes, so the output can't be told apart from it.
/// libavb never reads it.
const RELEASE_STRING: &str = "avbtool 1.3.0";

const HASHTREE_DESCRIPTOR_TAG: u64 = 1;
/// Fixed part of an `AvbHashtreeDescriptor`, tag and length fields included.
const HASHTREE_DESCRIPTOR_SIZE: usize = 180;

/// Android sparse image magic (little endian 0xED26FF3A).
const SPARSE_MAGIC: [u8; 4] = [0x3A, 0xFF, 0x26, 0xED];

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HashAlgorithm {
    Sha1,
    Sha256,
    Sha512,
}

impl HashAlgorithm {
    /// Name as stored in the descriptor.
    pub fn name(self) -> &'static str {
        match self {
            HashAlgorithm::Sha1 => "sha1",
            HashAlgorithm::Sha256 => "sha256",
            HashAlgorithm::Sha512 => "sha512",
        }
    }

    pub fn digest_size(self) -> usize {
        match self {
            HashAlgorithm::Sha1 => 20,
            HashAlgorithm::Sha256 => 32,
            HashAlgorithm::Sha512 => 64,
        }
    }
}

impl FromStr for HashAlgorithm {
    type Err = String;

    fn from_str(s: &str) -> Result<Self, String> {
        match s.to_ascii_lowercase().as_str() {
            "sha1" => Ok(HashAlgorithm::Sha1),
            "sha256" => Ok(HashAlgorithm::Sha256),
            "sha512" => Ok(HashAlgorithm::Sha512),
            _ => Err(format!(
                "unsupported hash algorithm: {} (use sha1, sha256 or sha512)",
                s
            )),
        }
    }
}

#[derive(Debug, Clone)]
pub struct HashtreeOptions {
    /// Partition name without A/B suffix, e.g. `system`.
    pub partition_name: String,
    pub hash_algorithm: HashAlgorithm,
    /// Fixed salt. `None` takes as many random bytes as the digest has.
    pub salt: Option<Vec<u8>>,
}

impl HashtreeOptions {
    /// sha256 and a random salt.
    pub fn new(partition_name: impl Into<String>) -> HashtreeOptions {
        HashtreeOptions {
            partition_name: partition_name.into(),
            hash_algorithm: HashAlgorithm::Sha256,
            salt: None,
        }
    }
}

/// What was appended to the image.
#[derive(Debug, Clone)]
pub struct HashtreeFooter {
    /// Size of the image before anything was appended (`original_image_size`).
    pub original_size: u64,
    /// Bytes covered by the hash tree: the original size rounded up to a block.
    pub image_size: u64,
    pub tree_offset: u64,
    pub tree_size: u64,
    pub vbmeta_offset: u64,
    pub vbmeta_size: u64,
    /// Size of the file now.
    pub final_size: u64,
    pub root_digest: Vec<u8>,
    pub salt: Vec<u8>,
}

/// Adds the hashtree footer to a raw image, in place. Fails (and leaves the
/// image at its original size) if anything goes wrong.
pub fn add_hashtree_footer(
    image: &Path,
    opts: &HashtreeOptions,
    mut log: impl FnMut(&str),
) -> io::Result<HashtreeFooter> {
    if opts.partition_name.is_empty() || opts.partition_name.contains('\0') {
        return Err(invalid("AVB partition name is empty or contains a NUL"));
    }
    let mut file = OpenOptions::new().read(true).write(true).open(image)?;
    let mut original = file.metadata()?.len();
    if original == 0 {
        return Err(invalid("the image is empty"));
    }

    let mut magic = [0u8; 4];
    file.read_exact(&mut magic)?;
    if magic == SPARSE_MAGIC {
        return Err(invalid(
            "sparse images are not supported, the AVB footer needs a raw image",
        ));
    }

    // an image that has a footer already goes back to its original size
    if original >= FOOTER_SIZE as u64 {
        file.seek(SeekFrom::Start(original - FOOTER_SIZE as u64))?;
        let mut raw = [0u8; FOOTER_SIZE];
        file.read_exact(&mut raw)?;
        if &raw[..4] == FOOTER_MAGIC {
            let before = u64::from_be_bytes(raw[12..20].try_into().unwrap());
            if before > 0 && before <= original {
                log(&format!(
                    "- Image already has an AVB footer, dropping it ({} -> {} bytes)",
                    original, before
                ));
                file.set_len(before)?;
                original = before;
            }
        }
    }

    append(&mut file, original, opts, &mut log).inspect_err(|_| {
        // back to the size it had
        let _ = file.set_len(original);
    })
}

fn append(
    file: &mut File,
    original: u64,
    opts: &HashtreeOptions,
    log: &mut impl FnMut(&str),
) -> io::Result<HashtreeFooter> {
    let alg = opts.hash_algorithm;
    let digest_size = alg.digest_size();
    // every digest takes a power of two bytes in the tree
    let digest_padding = digest_size.next_power_of_two() - digest_size;
    let stride = digest_size + digest_padding;

    // the data is hashed in whole blocks
    let image_size = original.next_multiple_of(BLOCK_SIZE);
    if image_size != original {
        file.set_len(image_size)?;
    }

    let salt = match &opts.salt {
        Some(salt) => salt.clone(),
        None => random_bytes(digest_size)?,
    };

    let (offsets, tree_size) = hash_level_offsets(image_size, stride as u64);
    log(&format!(
        "- AVB: hashing {} MiB with {} ({} byte blocks, {} KiB of tree)",
        image_size >> 20,
        alg.name(),
        BLOCK_SIZE,
        tree_size >> 10
    ));
    let (root_digest, tree) = match alg {
        HashAlgorithm::Sha1 => {
            hash_tree::<Sha1>(file, image_size, &salt, stride, &offsets, tree_size)
        }
        HashAlgorithm::Sha256 => {
            hash_tree::<Sha256>(file, image_size, &salt, stride, &offsets, tree_size)
        }
        HashAlgorithm::Sha512 => {
            hash_tree::<Sha512>(file, image_size, &salt, stride, &offsets, tree_size)
        }
    }?;

    let tree_offset = image_size;
    let mut padded_tree = tree;
    padded_tree.resize(padded_tree.len().next_multiple_of(BLOCK_SIZE as usize), 0);
    let vbmeta_offset = tree_offset + padded_tree.len() as u64;

    let descriptor = hashtree_descriptor(
        opts,
        image_size,
        tree_offset,
        tree_size,
        &salt,
        &root_digest,
    );
    let vbmeta = vbmeta_blob(&descriptor);
    let vbmeta_size = vbmeta.len() as u64;
    let mut padded_vbmeta = vbmeta;
    padded_vbmeta.resize(padded_vbmeta.len().next_multiple_of(BLOCK_SIZE as usize), 0);

    let footer = footer_block(original, vbmeta_offset, vbmeta_size);

    file.seek(SeekFrom::Start(tree_offset))?;
    file.write_all(&padded_tree)?;
    file.write_all(&padded_vbmeta)?;
    file.write_all(&footer)?;
    file.flush()?;
    let final_size = tree_offset + (padded_tree.len() + padded_vbmeta.len() + footer.len()) as u64;
    file.set_len(final_size)?;

    Ok(HashtreeFooter {
        original_size: original,
        image_size,
        tree_offset,
        tree_size,
        vbmeta_offset,
        vbmeta_size,
        final_size,
        root_digest,
        salt,
    })
}

fn random_bytes(n: usize) -> io::Result<Vec<u8>> {
    let mut out = vec![0u8; n];
    File::open("/dev/urandom")?.read_exact(&mut out)?;
    Ok(out)
}

/// Offset of every tree level and the size of the whole tree. Levels are
/// stored top first, so the lowest level (hashes of the data) comes last.
fn hash_level_offsets(image_size: u64, stride: u64) -> (Vec<u64>, u64) {
    let mut sizes = Vec::new();
    let mut tree_size = 0;
    let mut size = image_size;
    while size > BLOCK_SIZE {
        let blocks = size.div_ceil(BLOCK_SIZE);
        let level = (blocks * stride).next_multiple_of(BLOCK_SIZE);
        sizes.push(level);
        tree_size += level;
        size = level;
    }
    let offsets = (0..sizes.len())
        .map(|i| sizes[i + 1..].iter().sum())
        .collect();
    (offsets, tree_size)
}

/// Appends the salted digest of one block (zero padded to a whole block).
fn push_digest<D: Digest + Clone>(seeded: &D, data: &[u8], stride: usize, out: &mut Vec<u8>) {
    let mut hasher = seeded.clone();
    hasher.update(data);
    if data.len() < BLOCK_SIZE as usize {
        hasher.update(vec![0u8; BLOCK_SIZE as usize - data.len()]);
    }
    let start = out.len();
    out.extend_from_slice(&hasher.finalize()[..]);
    // each digest is followed by zeros up to the stride
    out.resize(start + stride, 0);
}

/// Merkle tree over the first `image_size` bytes of `file`. Returns the root
/// digest and the tree (`tree_size` bytes, levels laid out as in `offsets`).
fn hash_tree<D: Digest + Clone>(
    file: &mut File,
    image_size: u64,
    salt: &[u8],
    stride: usize,
    offsets: &[u64],
    tree_size: u64,
) -> io::Result<(Vec<u8>, Vec<u8>)> {
    let block = BLOCK_SIZE as usize;
    let seeded = D::new_with_prefix(salt);
    let mut tree = vec![0u8; tree_size as usize];

    file.seek(SeekFrom::Start(0))?;

    // one block: its digest is the root, there is no tree
    if image_size == BLOCK_SIZE {
        let mut data = vec![0u8; block];
        file.read_exact(&mut data)?;
        let mut hasher = seeded;
        hasher.update(&data);
        return Ok((hasher.finalize().to_vec(), tree));
    }

    // lowest level: straight from the file
    let mut level = Vec::with_capacity((image_size / BLOCK_SIZE) as usize * stride);
    let mut buf = vec![0u8; block * 256];
    let mut left = image_size;
    while left > 0 {
        let n = left.min(buf.len() as u64) as usize;
        file.read_exact(&mut buf[..n])?;
        for chunk in buf[..n].chunks(block) {
            push_digest(&seeded, chunk, stride, &mut level);
        }
        left -= n as u64;
    }

    let mut level_num = 0;
    loop {
        level.resize(level.len().next_multiple_of(block), 0);
        let at = *offsets
            .get(level_num)
            .ok_or_else(|| invalid("hash tree has more levels than expected"))?
            as usize;
        let end = at + level.len();
        if end > tree.len() {
            return Err(invalid("hash tree level does not fit its slot"));
        }
        tree[at..end].copy_from_slice(&level);
        level_num += 1;
        if level.len() as u64 <= BLOCK_SIZE {
            break;
        }
        // next level: digests of the one just stored
        let src_at = offsets[level_num - 1] as usize;
        let mut next = Vec::with_capacity(level.len() / block * stride);
        for chunk in tree[src_at..src_at + level.len()].chunks(block) {
            push_digest(&seeded, chunk, stride, &mut next);
        }
        level = next;
    }

    // `level` is the single top block
    let mut hasher = seeded;
    hasher.update(&level);
    Ok((hasher.finalize().to_vec(), tree))
}

/// `AvbHashtreeDescriptor` followed by partition name, salt and root digest,
/// padded to 8 bytes. No FEC.
fn hashtree_descriptor(
    opts: &HashtreeOptions,
    image_size: u64,
    tree_offset: u64,
    tree_size: u64,
    salt: &[u8],
    root_digest: &[u8],
) -> Vec<u8> {
    let name = opts.partition_name.as_bytes();
    let following = (HASHTREE_DESCRIPTOR_SIZE + name.len() + salt.len() + root_digest.len() - 16)
        .next_multiple_of(8);

    let mut d = Vec::with_capacity(16 + following);
    d.extend_from_slice(&HASHTREE_DESCRIPTOR_TAG.to_be_bytes());
    d.extend_from_slice(&(following as u64).to_be_bytes());
    d.extend_from_slice(&1u32.to_be_bytes()); // dm-verity version
    d.extend_from_slice(&image_size.to_be_bytes());
    d.extend_from_slice(&tree_offset.to_be_bytes());
    d.extend_from_slice(&tree_size.to_be_bytes());
    d.extend_from_slice(&(BLOCK_SIZE as u32).to_be_bytes()); // data block size
    d.extend_from_slice(&(BLOCK_SIZE as u32).to_be_bytes()); // hash block size
    d.extend_from_slice(&0u32.to_be_bytes()); // FEC roots
    d.extend_from_slice(&0u64.to_be_bytes()); // FEC offset
    d.extend_from_slice(&0u64.to_be_bytes()); // FEC size
    let mut algorithm = [0u8; 32];
    let alg_name = opts.hash_algorithm.name().as_bytes();
    algorithm[..alg_name.len()].copy_from_slice(alg_name);
    d.extend_from_slice(&algorithm);
    d.extend_from_slice(&(name.len() as u32).to_be_bytes());
    d.extend_from_slice(&(salt.len() as u32).to_be_bytes());
    d.extend_from_slice(&(root_digest.len() as u32).to_be_bytes());
    d.extend_from_slice(&0u32.to_be_bytes()); // flags
    d.extend_from_slice(&[0u8; 60]); // reserved
    d.extend_from_slice(name);
    d.extend_from_slice(salt);
    d.extend_from_slice(root_digest);
    d.resize(16 + following, 0);
    d
}

/// Unsigned vbmeta image (algorithm NONE): the header, an empty
/// authentication block and an auxiliary block that holds `descriptors`.
fn vbmeta_blob(descriptors: &[u8]) -> Vec<u8> {
    let aux_size = descriptors.len().next_multiple_of(64);
    let desc_len = descriptors.len() as u64;

    let mut h = Vec::with_capacity(VBMETA_HEADER_SIZE + aux_size);
    h.extend_from_slice(VBMETA_MAGIC);
    h.extend_from_slice(&1u32.to_be_bytes()); // required libavb 1.0
    h.extend_from_slice(&0u32.to_be_bytes());
    h.extend_from_slice(&0u64.to_be_bytes()); // authentication block size
    h.extend_from_slice(&(aux_size as u64).to_be_bytes());
    h.extend_from_slice(&0u32.to_be_bytes()); // algorithm NONE
    h.extend_from_slice(&[0u8; 16]); // hash offset, size
    h.extend_from_slice(&[0u8; 16]); // signature offset, size
    h.extend_from_slice(&desc_len.to_be_bytes()); // public key offset
    h.extend_from_slice(&0u64.to_be_bytes()); // public key size
    h.extend_from_slice(&desc_len.to_be_bytes()); // public key metadata offset
    h.extend_from_slice(&0u64.to_be_bytes()); // public key metadata size
    h.extend_from_slice(&0u64.to_be_bytes()); // descriptors offset
    h.extend_from_slice(&desc_len.to_be_bytes()); // descriptors size
    h.extend_from_slice(&0u64.to_be_bytes()); // rollback index
    h.extend_from_slice(&0u32.to_be_bytes()); // flags
    h.extend_from_slice(&0u32.to_be_bytes()); // rollback index location
    let mut release = [0u8; RELEASE_STRING_SIZE];
    release[..RELEASE_STRING.len()].copy_from_slice(RELEASE_STRING.as_bytes());
    h.extend_from_slice(&release);
    h.extend_from_slice(&[0u8; 80]); // reserved
    debug_assert_eq!(h.len(), VBMETA_HEADER_SIZE);

    // no authentication data; the auxiliary block follows the header
    h.extend_from_slice(descriptors);
    h.resize(VBMETA_HEADER_SIZE + aux_size, 0);
    h
}

/// The last block of the image: zeros, then the `AvbFooter`.
fn footer_block(original_size: u64, vbmeta_offset: u64, vbmeta_size: u64) -> Vec<u8> {
    let mut block = vec![0u8; BLOCK_SIZE as usize];
    let f = &mut block[BLOCK_SIZE as usize - FOOTER_SIZE..];
    f[..4].copy_from_slice(FOOTER_MAGIC);
    f[4..8].copy_from_slice(&1u32.to_be_bytes()); // footer version 1.0
    f[8..12].copy_from_slice(&0u32.to_be_bytes());
    f[12..20].copy_from_slice(&original_size.to_be_bytes());
    f[20..28].copy_from_slice(&vbmeta_offset.to_be_bytes());
    f[28..36].copy_from_slice(&vbmeta_size.to_be_bytes());
    block
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn temp(name: &str) -> std::path::PathBuf {
        std::env::temp_dir().join(format!("yuki-avb-{}-{}", std::process::id(), name))
    }

    /// Deterministic filler that doesn't compress to nothing.
    fn data(len: usize) -> Vec<u8> {
        let mut x = 0x1234_5678_9abc_def0u64;
        (0..len)
            .map(|_| {
                x ^= x << 13;
                x ^= x >> 7;
                x ^= x << 17;
                (x >> 24) as u8
            })
            .collect()
    }

    fn be64(b: &[u8], at: usize) -> u64 {
        u64::from_be_bytes(b[at..at + 8].try_into().unwrap())
    }

    #[test]
    fn level_offsets() {
        // 8 MiB: 2048 digests = 16 blocks, then 16 digests = 1 block
        let (offsets, size) = hash_level_offsets(8 << 20, 32);
        assert_eq!(offsets, vec![4096, 0]);
        assert_eq!(size, 65536 + 4096);
        // one block of data needs no tree
        assert_eq!(hash_level_offsets(4096, 32), (vec![], 0));
    }

    #[test]
    fn parse_algorithm() {
        assert_eq!("SHA256".parse::<HashAlgorithm>(), Ok(HashAlgorithm::Sha256));
        assert!("md5".parse::<HashAlgorithm>().is_err());
    }

    /// Recomputes the tree with a plain, level by level implementation.
    fn naive_tree(img: &[u8], salt: &[u8]) -> (Vec<u8>, Vec<u8>) {
        let mut levels: Vec<Vec<u8>> = Vec::new();
        let mut src = img.to_vec();
        while src.len() > 4096 {
            let mut out = Vec::new();
            for block in src.chunks(4096) {
                let mut h = Sha256::new();
                h.update(salt);
                h.update(block);
                out.extend_from_slice(&h.finalize());
            }
            out.resize(out.len().next_multiple_of(4096), 0);
            levels.push(out.clone());
            src = out;
        }
        let mut h = Sha256::new();
        h.update(salt);
        h.update(&src);
        let root = h.finalize().to_vec();
        // top level first
        let tree: Vec<u8> = levels.iter().rev().flatten().copied().collect();
        (root, tree)
    }

    #[test]
    fn footer_matches_naive_tree_and_is_idempotent() {
        let path = temp("a.img");
        // not a multiple of the block size on purpose
        let img = data(3 * 1024 * 1024 + 1500);
        fs::write(&path, &img).unwrap();

        let mut opts = HashtreeOptions::new("system");
        opts.salt = Some((0u8..32).collect());
        let info = add_hashtree_footer(&path, &opts, |_| {}).unwrap();
        let out = fs::read(&path).unwrap();

        assert_eq!(info.original_size, img.len() as u64);
        assert_eq!(info.image_size % 4096, 0);
        assert_eq!(out.len() as u64, info.final_size);
        assert_eq!(out.len() % 4096, 0);
        assert_eq!(&out[..img.len()], &img[..]);

        // footer in the last 64 bytes
        let f = &out[out.len() - FOOTER_SIZE..];
        assert_eq!(&f[..4], FOOTER_MAGIC);
        assert_eq!(be64(f, 12), img.len() as u64);
        assert_eq!(be64(f, 20), info.vbmeta_offset);
        assert_eq!(be64(f, 28), info.vbmeta_size);

        // vbmeta header and descriptor
        let vb = &out[info.vbmeta_offset as usize..];
        assert_eq!(&vb[..4], VBMETA_MAGIC);
        assert_eq!(be64(vb, 12), 0); // no authentication data
        let desc = &vb[VBMETA_HEADER_SIZE..];
        assert_eq!(be64(desc, 0), HASHTREE_DESCRIPTOR_TAG);
        assert_eq!(be64(desc, 20), info.image_size);
        assert_eq!(be64(desc, 28), info.tree_offset);
        assert_eq!(be64(desc, 36), info.tree_size);

        // tree and root digest against the naive version
        let mut padded = img.clone();
        padded.resize(info.image_size as usize, 0);
        let (root, tree) = naive_tree(&padded, &opts.salt.clone().unwrap());
        assert_eq!(info.root_digest, root);
        let at = info.tree_offset as usize;
        assert_eq!(&out[at..at + tree.len()], &tree[..]);

        // a second run gives the very same file
        add_hashtree_footer(&path, &opts, |_| {}).unwrap();
        assert_eq!(fs::read(&path).unwrap(), out);
        let _ = fs::remove_file(&path);
    }

    #[test]
    fn single_block_and_other_algorithms() {
        for alg in [
            HashAlgorithm::Sha1,
            HashAlgorithm::Sha256,
            HashAlgorithm::Sha512,
        ] {
            for len in [4096usize, 4097, 1 << 20] {
                let path = temp(&format!("{}-{}.img", alg.name(), len));
                fs::write(&path, data(len)).unwrap();
                let mut opts = HashtreeOptions::new("vendor");
                opts.hash_algorithm = alg;
                opts.salt = Some(vec![7; alg.digest_size()]);
                let info = add_hashtree_footer(&path, &opts, |_| {}).unwrap();
                assert_eq!(info.root_digest.len(), alg.digest_size());
                assert_eq!(fs::metadata(&path).unwrap().len(), info.final_size);
                let _ = fs::remove_file(&path);
            }
        }
    }

    #[test]
    fn rejects_sparse_and_empty() {
        let path = temp("sparse.img");
        let mut sparse = SPARSE_MAGIC.to_vec();
        sparse.resize(8192, 0);
        fs::write(&path, &sparse).unwrap();
        assert!(add_hashtree_footer(&path, &HashtreeOptions::new("system"), |_| {}).is_err());
        assert_eq!(fs::metadata(&path).unwrap().len(), 8192);
        fs::write(&path, b"").unwrap();
        assert!(add_hashtree_footer(&path, &HashtreeOptions::new("system"), |_| {}).is_err());
        let _ = fs::remove_file(&path);
    }
}
