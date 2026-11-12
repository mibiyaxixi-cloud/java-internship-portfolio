package com.bookflow.admin;

import com.bookflow.catalog.Device;
import com.bookflow.catalog.SlotRow;
import com.bookflow.reservation.ReservationView;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminService service;

    public AdminController(AdminService service) { this.service = service; }

    @GetMapping("/reservations")
    public List<ReservationView> reservations() { return service.reservations(); }

    @PostMapping("/devices") @ResponseStatus(HttpStatus.CREATED)
    public Device createDevice(@RequestBody DeviceInput input) { return service.createDevice(input); }

    @PutMapping("/devices/{id}")
    public Device updateDevice(@PathVariable long id, @RequestBody DeviceInput input) { return service.updateDevice(id, input); }

    @DeleteMapping("/devices/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDevice(@PathVariable long id) { service.deleteDevice(id); }

    @GetMapping("/devices/{id}/slots")
    public List<AdminSlot> slots(@PathVariable long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.slots(id, date);
    }

    @PostMapping("/devices/{id}/slots") @ResponseStatus(HttpStatus.CREATED)
    public SlotRow createSlot(@PathVariable long id, @RequestBody SlotInput input) { return service.createSlot(id, input); }

    @PutMapping("/slots/{id}")
    public SlotRow updateSlot(@PathVariable long id, @RequestBody SlotInput input) { return service.updateSlot(id, input); }

    @DeleteMapping("/slots/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSlot(@PathVariable long id) { service.deleteSlot(id); }
}
