//! JNI bridge between the `:yuki` Android module and `yuki-core`.
//!
//! Native methods are registered from `JNI_OnLoad`, so the Kotlin class name
//! lives in one place (`natives::BRIDGE_CLASS`) instead of in every symbol.

mod natives;
mod util;

use std::ffi::c_void;

use jni::sys::{jint, JNI_ERR, JNI_VERSION_1_6};
use jni::JavaVM;

#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut c_void) -> jint {
    let Ok(mut env) = vm.get_env() else {
        return JNI_ERR;
    };
    match natives::register(&mut env) {
        Ok(()) => JNI_VERSION_1_6,
        Err(_) => JNI_ERR,
    }
}
