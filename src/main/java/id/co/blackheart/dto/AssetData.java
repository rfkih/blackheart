package id.co.blackheart.dto;

import lombok.Data;


@Data
public class AssetData {
    private String asset;
    private String free;
    private String locked;

    // Getters and Setters
    public String getAsset() { return asset; }
    public void setAsset(String asset) { this.asset = asset; }
    public String getFree() { return free; }
    public void setFree(String free) { this.free = free; }
    public String getLocked() { return locked; }
    public void setLocked(String locked) { this.locked = locked; }

    @Override
    public String toString() {
        return "AssetData{" +
                "asset='" + asset + '\'' +
                ", free='" + free + '\'' +
                ", locked='" + locked + '\'' +
                '}';
    }
}
