package com.cabeye.backend.admin;

import com.cabeye.backend.store.DataDirectory;
import com.cabeye.backend.store.Table;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Small admin settings kept in the database, e.g. the linked Telegram chat. */
@Component
public class AdminSettings {

    static final String TELEGRAM_CHAT = "telegram.chatId";
    static final String TELEGRAM_NAME = "telegram.chatName";

    private final Table<Row> table;

    public AdminSettings(DataDirectory data) {
        this.table = data.table("admin_settings", Row.class);
    }

    public Optional<String> get(String key) {
        return table.get(key).map(r -> r.value).filter(v -> !v.isBlank());
    }

    public void put(String key, String value) {
        Row r = new Row();
        r.value = value;
        table.put(key, r);
    }

    public void remove(String key) {
        table.remove(key);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Row {
        public String value;

        public Row() {}
    }
}
