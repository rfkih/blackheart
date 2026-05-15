package id.co.blackheart.dto.request;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class ResearchPauseRequest {
    /** Optional human-readable reason. Surfaced in the dashboard banner so the
     *  next operator (or future-you) knows why ticks were paused. */
    private String reason;
}
