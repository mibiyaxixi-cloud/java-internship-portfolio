package com.bookflow.catalog;

import com.bookflow.common.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogServiceTest {
    private final CatalogMapper mapper = mock(CatalogMapper.class);
    private final Clock clock = Clock.fixed(Instant.parse("2030-01-01T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private final CatalogService service = new CatalogService(mapper, clock);
    private final Device device = new Device(1, "TEST", "Test microscope", "Imaging", "Lab A", "", "ACTIVE", false);
    private final LocalDateTime now = LocalDateTime.of(2030, 1, 1, 10, 0);

    private SlotRow slot(LocalDateTime start, LocalDateTime end, String status, boolean occupied) {
        return new SlotRow(1, 1, start, end, status, false, occupied);
    }

    @Test
    void exactStartAndEndAreNotAvailable() {
        assertThat(CatalogService.availability(device, slot(now, now.plusHours(1), "OPEN", false), now)).isEqualTo("STARTED");
        assertThat(CatalogService.availability(device, slot(now.minusHours(1), now, "OPEN", false), now)).isEqualTo("ENDED");
    }

    @Test
    void futureSlotReflectsClosureAndOccupancy() {
        SlotRow open = slot(now.plusHours(1), now.plusHours(2), "OPEN", false);
        Device inactive = new Device(1, "TEST", "Test", "Test", "Lab", "", "INACTIVE", false);
        assertThat(CatalogService.availability(device, open, now)).isEqualTo("AVAILABLE");
        assertThat(CatalogService.availability(inactive, open, now)).isEqualTo("CLOSED");
        assertThat(CatalogService.availability(device, slot(now.plusHours(1), now.plusHours(2), "CLOSED", false), now)).isEqualTo("CLOSED");
        assertThat(CatalogService.availability(device, slot(now.plusHours(1), now.plusHours(2), "OPEN", true), now)).isEqualTo("OCCUPIED");
    }

    @Test
    void omittedDateUsesLaboratoryDateAndDayBoundaries() {
        when(mapper.findDevice(1)).thenReturn(device);
        when(mapper.findSlots(1, LocalDate.of(2030, 1, 1).atStartOfDay(), LocalDate.of(2030, 1, 2).atStartOfDay()))
                .thenReturn(List.of());
        DailySlots result = service.listSlots(1, null);
        assertThat(result.date()).isEqualTo(LocalDate.of(2030, 1, 1));
        assertThat(result.timeZone()).isEqualTo("Asia/Shanghai");
        assertThat(result.slots()).isEmpty();
        verify(mapper).findSlots(1, LocalDate.of(2030, 1, 1).atStartOfDay(), LocalDate.of(2030, 1, 2).atStartOfDay());
    }

    @Test
    void missingDeviceAndInvalidInputAreExplicitErrors() {
        assertThatThrownBy(() -> service.listSlots(42, null)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.listSlots(-1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.listSlots(1, LocalDate.of(9999, 1, 1))).isInstanceOf(IllegalArgumentException.class);
    }
}
