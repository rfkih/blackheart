package id.co.blackheart.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "server_ip_log")
@Getter
@Setter
public class ServerIpLog {


    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "server_ip_log_id", nullable = false, updatable = false)
    private UUID serverIpLogId;

    @Column(name = "ip_address", nullable = false)
    private String ipAddress;

    @Column(name = "previous_ip")
    private String previousIp;

    @Column(name = "event", nullable = false)
    private String event; // INIT, CHANGED, UNCHANGED

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;
}