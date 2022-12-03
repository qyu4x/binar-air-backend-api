package com.binarair.binarairrestapi.controller;


import com.binarair.binarairrestapi.model.request.BagageRequest;
import com.binarair.binarairrestapi.model.request.BenefitRequest;
import com.binarair.binarairrestapi.model.response.BagageResponse;
import com.binarair.binarairrestapi.model.response.BenefitDetailResponse;
import com.binarair.binarairrestapi.model.response.WebResponse;
import com.binarair.binarairrestapi.service.BagageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/v1/bagage")
public class BagageController {

    private final static Logger log = LoggerFactory.getLogger(BagageController.class);

    private final BagageService bagageService;

    @Autowired
    public BagageController(BagageService bagageService) {
        this.bagageService = bagageService;
    }

    @PostMapping
    @ResponseBody
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<WebResponse<BagageResponse>> save(@Valid @RequestBody BagageRequest bagageRequest) {
        log.info("call controller save - bagage");
        BagageResponse bagageResponse = bagageService.save(bagageRequest);
        log.info("successful save bagage data");
        WebResponse webResponse = new WebResponse(
                HttpStatus.CREATED.value(),
                HttpStatus.CREATED.getReasonPhrase(),
                bagageResponse
        );
        return new ResponseEntity<>(webResponse, HttpStatus.CREATED);
    }

    @ResponseBody
    @GetMapping("/all")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_BUYER')")
    public ResponseEntity<WebResponse<List<BagageResponse>>> getAll() {
        log.info("Calling controller getAll - bagage");
        List<BagageResponse> bagageResponses = bagageService.getAll();
        WebResponse webResponse = new WebResponse(
                HttpStatus.OK.value(),
                HttpStatus.OK.getReasonPhrase(),
                bagageResponses
        );
        log.info("Successful get all bagage data");
        return new ResponseEntity<>(webResponse, HttpStatus.OK);
    }
    @ResponseBody
    @GetMapping
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_BUYER')")
    public ResponseEntity<WebResponse<BagageResponse>> findByAircraftId(@RequestParam("aircraftid") String aircraftid) {
        log.info("Calling controller find by aircraft id - bagage");
        BagageResponse bagageResponse = bagageService.findBagageByAircraftId(aircraftid);
        WebResponse webResponse = new WebResponse(
                HttpStatus.OK.value(),
                HttpStatus.OK.getReasonPhrase(),
                bagageResponse
        );
        log.info("Successful get bagage data based on id aircraft");
        return new ResponseEntity<>(webResponse, HttpStatus.OK);
    }
}
