package com.citypass.utils;

import com.citypass.dto.UserDTO;

public class UserHolder {

    private static final ThreadLocal<UserDTO> userThreadLocal = new ThreadLocal<>();

    public static void saveUser(UserDTO user) {
        userThreadLocal.set(user);
    }

    public static UserDTO getUser() {
        return userThreadLocal.get();
    }

    public static void removeUser() {
        userThreadLocal.remove();
    }
}
