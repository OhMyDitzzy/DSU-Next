use std::any::Any;
use std::fmt::Display;
use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::objects::{JObject, JObjectArray, JString, JValue};
use jni::sys::{jobjectArray, jsize};
use jni::JNIEnv;

pub type Res<T> = Result<T, String>;

pub const EXCEPTION_CLASS: &str = "com/ditzzy/dsunext/yuki/YukiException";

pub fn msg<E: Display>(e: E) -> String {
    e.to_string()
}

pub fn throw(env: &mut JNIEnv, message: &str) {
    if env.exception_check().unwrap_or(true) {
        return;
    }
    let _ = env.throw_new(EXCEPTION_CLASS, message);
}

fn panic_message(payload: &(dyn Any + Send)) -> String {
    if let Some(s) = payload.downcast_ref::<&str>() {
        (*s).to_string()
    } else if let Some(s) = payload.downcast_ref::<String>() {
        s.clone()
    } else {
        "unknown panic".to_string()
    }
}

/// Runs `body`, turning an error or a panic into a `YukiException` and
/// returning `fallback`. A panic must never unwind into the JVM.
pub fn guard<'l, T>(
    env: &mut JNIEnv<'l>,
    fallback: T,
    body: impl FnOnce(&mut JNIEnv<'l>) -> Res<T>,
) -> T {
    let outcome = catch_unwind(AssertUnwindSafe(|| body(&mut *env)));
    match outcome {
        Ok(Ok(value)) => value,
        Ok(Err(message)) => {
            throw(env, &message);
            fallback
        }
        Err(payload) => {
            throw(
                env,
                &format!("native panic: {}", panic_message(payload.as_ref())),
            );
            fallback
        }
    }
}

/// Fails when the logger callback left a Java exception pending: no other JNI
/// call is allowed until it is handled, and the exception reaches the caller.
pub fn pending<'l>(env: &mut JNIEnv<'l>) -> Res<()> {
    match env.exception_check() {
        Ok(false) => Ok(()),
        _ => Err("YukiLogger threw an exception".to_string()),
    }
}

pub fn string<'l>(env: &mut JNIEnv<'l>, s: &JString<'l>) -> Res<String> {
    env.get_string(s).map(String::from).map_err(msg)
}

pub fn opt_string<'l>(env: &mut JNIEnv<'l>, o: JObject<'l>) -> Res<Option<String>> {
    if o.is_null() {
        return Ok(None);
    }
    string(env, &JString::from(o)).map(Some)
}

/// `None` for a null or empty array, which both mean "everything".
pub fn opt_strings<'l>(env: &mut JNIEnv<'l>, o: JObject<'l>) -> Res<Option<Vec<String>>> {
    if o.is_null() {
        return Ok(None);
    }
    let array = JObjectArray::from(o);
    let len = env.get_array_length(&array).map_err(msg)?;
    let mut out = Vec::with_capacity(len as usize);
    for i in 0..len {
        let item = JString::from(env.get_object_array_element(&array, i).map_err(msg)?);
        out.push(string(env, &item)?);
        let _ = env.delete_local_ref(item);
    }
    Ok((!out.is_empty()).then_some(out))
}

pub fn string_array<'l>(env: &mut JNIEnv<'l>, items: &[String]) -> Res<jobjectArray> {
    let array = env
        .new_object_array(items.len() as jsize, "java/lang/String", JObject::null())
        .map_err(msg)?;
    for (i, item) in items.iter().enumerate() {
        let text = env.new_string(item).map_err(msg)?;
        env.set_object_array_element(&array, i as jsize, &text)
            .map_err(msg)?;
        let _ = env.delete_local_ref(text);
    }
    Ok(array.into_raw())
}

/// Forwards every log line to `YukiLogger.onLog`. After the first failed call
/// (a Java exception is pending) it stops calling into Java.
pub fn logger<'a, 'l>(
    env: &'a mut JNIEnv<'l>,
    target: &'a JObject<'l>,
) -> impl FnMut(&str) + use<'a, 'l> {
    let mut dead = false;
    move |line: &str| {
        if dead || target.is_null() {
            return;
        }
        let Ok(text) = env.new_string(line) else {
            dead = true;
            return;
        };
        let arg: &JObject = &text;
        let sent = env.call_method(
            target,
            "onLog",
            "(Ljava/lang/String;)V",
            &[JValue::Object(arg)],
        );
        if sent.is_err() {
            dead = true;
        }
        let _ = env.delete_local_ref(text);
    }
}
