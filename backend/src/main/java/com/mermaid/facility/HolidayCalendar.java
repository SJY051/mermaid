package com.mermaid.facility;

import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * Is a given date a Korean public holiday?
 *
 * <p>Matters because both public APIs keep a separate holiday row (the pharmacy API's {@code
 * dutyTime8s}/{@code dutyTime8c}; HIRA's {@code noTrmtHoli}). Getting this wrong means telling
 * someone a pharmacy is open on 설날.
 *
 * <p>TODO(team): back this with 한국천문연구원 특일 정보 API (data.go.kr 15012690,
 * {@code getRestDeInfo}), and cache a year at a time. Until then everything is a weekday, which is
 * wrong on ~15 days a year — acceptable for the demo, not for real use.
 */
@Component
public class HolidayCalendar {

    public boolean isHoliday(LocalDate date) {
        return false;
    }
}
