package id.co.blackheart.util;

import lombok.Getter;

@Getter
public enum ResponseCode {

    SUCCESS("00", "Success"),

    // Internal Response
    INVALID_TRANSACTION("12", "Invalid Transaction"),
    INVALID_AMOUNT("13", "Jumlah Tidak Sesuai"),
    ACCOUNT_NOT_FOUND("14", "Rekening/Data Tidak ditemukan"),
    INVALID_ACCOUNT("15", "Rekening Blokir / Tidak Aktif"),
    WRONG_FORMAT_DATA("30", "Format Data Salah"),
    TAGIHAN_SUDAH_TERBAYAR("88","Tagihan Bulan ini Sudah Terbayar"),
    INVALID_RESPONSE_CODE("31","Response Code Tidak Sesuai"),



    // Client Errors
    BAD_REQUEST_REQUIRED_FIELD_MISSING("01", "Bad Request - Required Field Missing"),
    BAD_REQUEST_INVALID_INPUT("02", "Bad Request - Invalid Input"),
    NOT_FOUND("04", "Not Found"),
    ACCESS_DENIED("05", "Access Denied"),

    // Server Errors
    INTERNAL_SERVER_ERROR("50", "Internal Server Error"),
    CUSTOM_ERROR("51", "Custom Error"),
    DATABASE_ERROR("52", "Database Error"),
    TIMEOUT_ERROR("53", "Timeout Error"),
    NETWORK_ERROR("54", "Network Error"),
    OUT_OF_MEMORY("55", "Out of Memory"),
    DEADLOCK("56", "Deadlock"),

    // Validation Errors
    ILLEGAL_ARGUMENT("61", "Validation Error - Illegal Argument"),
    ILLEGAL_STATE("62", "Validation Error - Illegal State"),
    DATA_INTEGRITY_ERROR("63", "Validation Error - Data Integrity"),
    FILE_NOT_READABLE("64", "Validation Error - File not Readable"),
    FILE_NOT_WRITABLE("65", "Validation Error - File not Writable"),
    FILE_NOT_FOUND("66", "Validation Error - File not Found"),
    CLASS_NOT_FOUND("67", "Validation Error - Class not Found"),
    METHOD_NOT_FOUND("68", "Validation Error - Method not Found"),
    UNSUPPORTED_OPERATION("69", "Validation Error - Unsupported Operation"),
    ARITHMETIC_EXCEPTION("70", "Validation Error - Arithmetic Exception"),
    INDEX_OUT_OF_BOUNDS("71", "Validation Error - Index out of Bounds"),
    SOCKET_TIMEOUT("72", "Validation Error - Socket Timeout"),
    CONNECTION_REFUSED("73", "Validation Error - Connection Refused"),
    MALFORMED_URL("74", "Validation Error - Malformed URL"),
    JSON_PARSING_ERROR("75", "Validation Error - JSON Parsing"),
    XML_PARSING_ERROR("76", "Validation Error - XML Parsing"),
    TOO_MANY_REQUESTS("80", "Too Many Requests");

    private final String code;
    private final String description;

    // Constructor for each enum constant
    ResponseCode(String code, String description) {
        this.code = code;
        this.description = description;
    }

}