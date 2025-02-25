package id.co.blackheart.util;

public class ResponseService {
    Object responseCode;
    Object responseDesc;
    Object responseData;

    public ResponseService() {
    }

    public ResponseService(Object responseCode, Object responseDesc, Object responseData) {
        this.responseCode = responseCode;
        this.responseDesc = responseDesc;
        this.responseData = responseData;
    }

    public Object getResponseCode() {
        return this.responseCode;
    }

    public void setResponseCode(Object responseCode) {
        this.responseCode = responseCode;
    }

    public Object getResponseDesc() {
        return this.responseDesc;
    }

    public void setResponseDesc(Object responseDesc) {
        this.responseDesc = responseDesc;
    }

    public Object getResponseData() {
        return this.responseData;
    }

    public void setResponseData(Object responseData) {
        this.responseData = responseData;
    }
}