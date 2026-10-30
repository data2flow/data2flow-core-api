package net.java21.data2flow.core.energy.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.energy.dto.VirtualSensorDtos.AchSeries;
import net.java21.data2flow.core.energy.dto.VirtualSensorDtos.SensorView;
import net.java21.data2flow.core.energy.dto.VirtualSensorDtos.UpdateRequest;
import net.java21.data2flow.core.energy.service.VirtualSensorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 공간 가상 센서(ENE-02, API-ENE-20·21): 조회 ENE_READ, 변경 ENE_ADMIN */
@RestController
public class VirtualSensorController {

    private final VirtualSensorService service;

    public VirtualSensorController(VirtualSensorService service) {
        this.service = service;
    }

    @GetMapping("/core/energy/spaces/{spaceId}/virtual-sensors")
    public ApiResponse<List<SensorView>> list(@PathVariable String spaceId) {
        return ApiResponse.success(service.list(spaceId));
    }

    @PutMapping("/core/energy/spaces/{spaceId}/virtual-sensors/{kind}")
    public ApiResponse<SensorView> update(@PathVariable String spaceId, @PathVariable String kind,
                                          @RequestBody(required = false) UpdateRequest body) {
        return ApiResponse.success(service.update(spaceId, kind, body));
    }

    @GetMapping("/core/energy/spaces/{spaceId}/ach")
    public ApiResponse<AchSeries> ach(@PathVariable String spaceId, @RequestParam(required = false) String from,
                                      @RequestParam(required = false) String to) {
        return ApiResponse.success(service.ach(spaceId, from, to));
    }
}
