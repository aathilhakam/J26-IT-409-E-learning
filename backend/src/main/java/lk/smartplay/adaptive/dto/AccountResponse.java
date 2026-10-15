package lk.smartplay.adaptive.dto;

import lk.smartplay.adaptive.model.TeacherAccount;

public record AccountResponse(String id, String name, String email) {
    public static AccountResponse from(TeacherAccount account) {
        return new AccountResponse(account.getId(), account.getName(), account.getEmail());
    }
}
