mod diagnosis;
mod session;
mod tools;
mod window;

use std::sync::Mutex;

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jlong, jstring};

use session::Handle;

fn handle<'a>(session: jlong) -> Option<&'a Mutex<session::Session>> {
    if session == 0 {
        None
    } else {
        Some(unsafe { &*(session as *const Handle) })
    }
}

fn java_string(env: &mut JNIEnv, value: String) -> jstring {
    env.new_string(value)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_pmcl_core_agent_LogAgentNative_open0(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    Box::into_raw(Box::new(Mutex::new(session::Session::new()))) as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_pmcl_core_agent_LogAgentNative_addFile0(
    mut env: JNIEnv,
    _class: JClass,
    session: jlong,
    name: JString,
    source_type: JString,
    content: JString,
) {
    let Some(handle) = handle(session) else {
        return;
    };
    let Ok(name) = env.get_string(&name) else {
        return;
    };
    let Ok(source_type) = env.get_string(&source_type) else {
        return;
    };
    let Ok(content) = env.get_string(&content) else {
        return;
    };
    if let Ok(mut session) = handle.lock() {
        session.add_file(&name.to_string_lossy(), &source_type.to_string_lossy(), &content.to_string_lossy());
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_pmcl_core_agent_LogAgentNative_setModsDir0(
    mut env: JNIEnv,
    _class: JClass,
    session: jlong,
    path: JString,
) {
    let Some(handle) = handle(session) else {
        return;
    };
    let Ok(path) = env.get_string(&path) else {
        return;
    };
    if let Ok(mut session) = handle.lock() {
        session.set_mods_dir(&path.to_string_lossy());
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_pmcl_core_agent_LogAgentNative_context0(
    env: JNIEnv,
    _class: JClass,
    session: jlong,
) -> jstring {
    let mut env = env;
    let Some(handle) = handle(session) else {
        return std::ptr::null_mut();
    };
    let text = handle
        .lock()
        .map(|session| {
            std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| session.context()))
                .unwrap_or_else(|_| "{\"error\":\"diagnostic engine failed\"}".to_string())
        })
        .unwrap_or_else(|_| "{\"error\":\"LogAgent session unavailable\"}".to_string());
    java_string(&mut env, text)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_pmcl_core_agent_LogAgentNative_tool0(
    mut env: JNIEnv,
    _class: JClass,
    session: jlong,
    request: JString,
) -> jstring {
    let Some(handle) = handle(session) else {
        return std::ptr::null_mut();
    };
    let Ok(request) = env.get_string(&request) else {
        return std::ptr::null_mut();
    };
    let request = request.to_string_lossy().to_string();
    let text = handle
        .lock()
        .map(|mut session| {
            std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| session.tool(&request)))
                .unwrap_or_else(|_| "{\"error\":\"diagnostic engine failed\"}".to_string())
        })
        .unwrap_or_else(|_| "{\"error\":\"LogAgent session unavailable\"}".to_string());
    java_string(&mut env, text)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_pmcl_core_agent_LogAgentNative_close0(
    _env: JNIEnv,
    _class: JClass,
    session: jlong,
) {
    if session == 0 {
        return;
    }
    unsafe {
        drop(Box::from_raw(session as *mut Handle));
    }
}
