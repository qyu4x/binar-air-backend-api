package com.binarair.binarairrestapi.service.impl;

import com.binarair.binarairrestapi.exception.DataNotFoundException;
import com.binarair.binarairrestapi.exception.ValidationException;
import com.binarair.binarairrestapi.model.entity.*;
import com.binarair.binarairrestapi.model.request.BookingAircraftSeatRequest;
import com.binarair.binarairrestapi.model.request.BookingDetailRequest;
import com.binarair.binarairrestapi.model.request.BookingPassengerRequest;
import com.binarair.binarairrestapi.model.response.*;
import com.binarair.binarairrestapi.repository.*;
import com.binarair.binarairrestapi.service.BookingDetailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class BookingDetailServiceImpl implements BookingDetailService {

    private final static Logger log = LoggerFactory.getLogger(BookingDetailServiceImpl.class);

    private final BookingDetailRepository bookingDetailRepository;

    private final BookingRepository bookingRepository;

    private final PassengerRepository passengerRepository;

    private final ScheduleRepository scheduleRepository;

    private final UserRepository userRepository;


    private final AircraftSeatRepository aircraftSeatRepository;

    private final SeatScheduleBookingRepository seatScheduleBookingRepository;

    private final TitelRepository titelRepository;

    private final AgeCategoryRepository ageCategoryRepository;

    private final CountryRepository countryRepository;

    private final BagageRepository bagageRepository;



    @Autowired
    public BookingDetailServiceImpl(BookingDetailRepository bookingDetailRepository, BookingRepository bookingRepository, PassengerRepository passengerRepository, ScheduleRepository scheduleRepository, UserRepository userRepository, AircraftSeatRepository aircraftSeatRepository, SeatScheduleBookingRepository seatScheduleBookingRepository, TitelRepository titelRepository, AgeCategoryRepository ageCategoryRepository, CountryRepository countryRepository, BagageRepository bagageRepository) {
        this.bookingDetailRepository = bookingDetailRepository;
        this.bookingRepository = bookingRepository;
        this.passengerRepository = passengerRepository;
        this.scheduleRepository = scheduleRepository;
        this.userRepository = userRepository;
        this.aircraftSeatRepository = aircraftSeatRepository;
        this.seatScheduleBookingRepository = seatScheduleBookingRepository;
        this.titelRepository = titelRepository;
        this.ageCategoryRepository = ageCategoryRepository;
        this.countryRepository = countryRepository;
        this.bagageRepository = bagageRepository;
    }

    @Override
    public BookingResponse transaction(BookingDetailRequest bookingDetailRequest, String userId) {
        log.info("Start a transaction");
        Booking booking = createBooking(bookingDetailRequest, userId);
        List<ProcessPassengerResponse> processPassengerRespons = insertPassenger(bookingDetailRequest, userId);
        processPassengerRespons.stream().forEach(processPassengerResponse -> {
            saveBookingDetail(processPassengerResponse, booking);
            updateScheduleStock(processPassengerResponse.getScheduleId());
        });
        String total = getTotalPayment(booking.getId()).toString().split(Pattern.quote("."))[0];
        boolean isSame = total.equals(bookingDetailRequest.getAmount().toString());
        if (!isSame) {
            throw new ValidationException(String.format("Total price of the backend and front end is not the same BACKEND : Rp. %s FRONTEND : Rp. %s ", total, bookingDetailRequest.getAmount()));
        }

        updateTotalPaidBooking(booking.getId(), bookingDetailRequest.getAmount());
        log.info("Successull transaction for user id %s {} ", userId);
        return getBookingResponse(booking.getId());
    }

    @Override
    public Booking createBooking(BookingDetailRequest bookingDetailRequest, String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new DataNotFoundException(String.format("User account not found")));
        String[] random = UUID.randomUUID().toString().toUpperCase().split("-");
        String numberBookingReferenceNumber = random[0];
        Booking booking = Booking.builder()
                .id(String.format("bo-%s", UUID.randomUUID().toString()))
                .bookingReferenceNumber(numberBookingReferenceNumber)
                .user(user)
                .bookingType(bookingDetailRequest.getBookingType())
                .createdAt(LocalDateTime.now())
                .build();
        log.info("Do create order for email {} ", user.getEmail());
        bookingRepository.save(booking);
        log.info("Successful create order");
        return booking;
    }

    @Override
    public List<ProcessPassengerResponse> insertPassenger(BookingDetailRequest bookingDetailRequest, String userId) {
        log.info("Do process insert passenger into database");
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new DataNotFoundException("User account not found"));
        List<BookingPassengerRequest> bookingPassengerDepartureRequests = bookingDetailRequest.getDepartures().getData();
        bookingPassengerDepartureRequests.stream().forEach(bookingPassengerRequest -> {
            bookingPassengerRequest.setStatus("departure");
        });

        List<BookingPassengerRequest> bookingPassengerRuturnRequests = new ArrayList<>();
        if(bookingDetailRequest.getReturns() == null) {
            bookingPassengerRuturnRequests =  List.of();
        } else {
            bookingPassengerRuturnRequests = bookingDetailRequest.getReturns().getData() == null ? List.of() : bookingDetailRequest.getReturns().getData();
            bookingPassengerRuturnRequests.stream().filter(Objects::nonNull).forEach(bookingPassengerRequest -> {
                bookingPassengerRequest.setStatus("return");
            });
        }

        List<BookingPassengerRequest> bookingPassengerRequests = Stream.of(bookingPassengerDepartureRequests, bookingPassengerRuturnRequests)
                .flatMap(Collection::stream)
                .collect(Collectors.toList());

        List<ProcessPassengerResponse> processPassengerRespons = new ArrayList<>();
        List<Passenger> passengers = new ArrayList<>();
        bookingPassengerRequests.stream().forEach(passengersRequest -> {
            AgeCategory ageCategory = ageCategoryRepository.findById(passengersRequest.getAgeCategoryId())
                    .orElseThrow(() -> new DataNotFoundException("Age category not found"));

            Country citizenship = countryRepository.findById(passengersRequest.getCitizenshipId())
                    .orElseThrow(() -> new DataNotFoundException("Citizenship not found"));

            Country issuingCountry = countryRepository.findById(passengersRequest.getIssuingCountryId())
                    .orElseThrow(() -> new DataNotFoundException("Issuing country not found"));

            Titel titel = titelRepository.findById(passengersRequest.getTitelId())
                    .orElseThrow(() -> new DataNotFoundException("Titel not found"));
            log.info("Do save data seat booking");
            AircraftSeatResponse aircraftSeatResponse = insertSeatBooking(passengersRequest.getAircraftSeat(), passengersRequest.getScheduleId());
            ProcessBaggageResponse baggage = findBaggageByScheduleId(passengersRequest.getScheduleId());
            baggage.setExtraBagage(passengersRequest.getBaggage().getTotal());
            log.info("Successful save data seat booking");
            Passenger passenger = Passenger.builder()
                    .id(String.format("ps-%s", UUID.randomUUID().toString()))
                    .user(user)
                    .cityzenship(citizenship)
                    .issuingCountry(issuingCountry)
                    .titel(titel)
                    .ageCategory(ageCategory)
                    .firstName(passengersRequest.getFirstName())
                    .lastName(passengersRequest.getLastName())
                    .birthDate(passengersRequest.getBirthDate())
                    .passportNumber(passengersRequest.getPassportNumber())
                    .build();
            passengers.add(passenger);
            ProcessPassengerResponse processPassengerResponse = ProcessPassengerResponse.builder()
                    .id(passenger.getId())
                    .scheduleId(passengersRequest.getScheduleId())
                    .user(passenger.getUser())
                    .status(passengersRequest.getStatus())
                    .cityzenship(citizenship)
                    .issuingCountry(issuingCountry)
                    .titel(titel)
                    .ageCategory(ageCategory)
                    .firstName(passenger.getFirstName())
                    .lastName(passenger.getLastName())
                    .birthDate(passenger.getBirthDate())
                    .passportNumber(passenger.getPassportNumber())
                    .seatResponse(aircraftSeatResponse)
                    .baggageResponse(baggage)
                    .build();
            processPassengerRespons.add(processPassengerResponse);
        });
        log.info("Do save all passengers");
        passengerRepository.saveAll(passengers);
        log.info("Succesfull save all passengers");
        return processPassengerRespons;
    }

    @Override
    @Transactional
    public AircraftSeatResponse insertSeatBooking(BookingAircraftSeatRequest bookingAircraftSeatRequest, String scheduleId) {
        SeatScheduleBooking isExists = seatScheduleBookingRepository.checkSeatStatus(scheduleId, bookingAircraftSeatRequest.getId());
        if (isExists != null) {
            throw new ValidationException(String.format("Seat with id %s and schedule id %s has been booked",bookingAircraftSeatRequest.getId(), scheduleId ));
        }

        AircraftSeat aircraftSeat = aircraftSeatRepository.findById(bookingAircraftSeatRequest.getId())
                .orElseThrow(() -> new DataNotFoundException(String.format("Aircraft seat not found")));

        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new DataNotFoundException(String.format("Schedule with id %s not found", scheduleId)));
        SeatScheduleBooking seatScheduleBooking = SeatScheduleBooking.builder()
                .id(String.format("sso-%s", UUID.randomUUID().toString()))
                .aircraftSeat(aircraftSeat)
                .schedule(schedule)
                .bookingDate(schedule.getDepartureDate())
                .createdAt(LocalDateTime.now())
                .build();
        log.info("Do update seat booking");
        seatScheduleBookingRepository.save(seatScheduleBooking);
        log.info("Successfull update seat booking");

        return AircraftSeatResponse.builder()
                .seatId(aircraftSeat.getId())
                .seatCode(aircraftSeat.getSeatCode())
                .status(false)
                .price(PriceResponse.builder()
                        .amount(aircraftSeat.getPrice())
                        .display(convertToDisplayCurrency(aircraftSeat.getPrice()))
                        .currencyCode(getIndonesiaCurrencyCode())
                        .build())
                .aircraftModel(aircraftSeat.getAircraft().getModel())
                .createdAt(aircraftSeat.getCreatedAt())
                .build();
    }

    @Override
    public ProcessBaggageResponse findBaggageByScheduleId(String scheduleId) {
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new DataNotFoundException(String.format("Schedule with id %s not found", scheduleId)));
        log.info("Do get bagage by id aircraft");
        Bagage bagage = bagageRepository.findByAircraftId(schedule.getAircraft().getId());
        return ProcessBaggageResponse.builder()
                .id(bagage.getId())
                .bagagePricePer5kg(bagage.getBagagePricePer5kg())
                .freeCabinCapacity(bagage.getFreeCabinBagageCapacity())
                .freeBagageCapacity(bagage.getFreeBagageCapacity())
                .createdAt(bagage.getCreatedAt())
                .aircraftModel(schedule.getAircraft().getModel())
                .aircraftManufacture(schedule.getAircraft().getAircraftManufacture().getName())
                .build();
    }

    @Override
    public void updateScheduleStock(String scheduleId) {
        log.info("Do update stock and sold");
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new DataNotFoundException(String.format("Schedule with id %s not found", scheduleId)));
        schedule.setStock(schedule.getStock() - 1);
        schedule.setSold(schedule.getSold() == null ? 1 : schedule.getSold() + 1);
        scheduleRepository.save(schedule);
        log.info("Succesful update stock and sold");
    }

    @Override
    public void updateTotalPaidBooking(String bookingId, BigDecimal total) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new DataNotFoundException(String.format("Booking with id %s not found", bookingId)));
        booking.setTotal(total);
        log.info("Do update total booking");
        bookingRepository.save(booking);
        log.info("Successul update total booking");
    }

    @Override
    public BookingDetail saveBookingDetail(ProcessPassengerResponse processPassengerResponses, Booking booking) {
        Schedule schedule = scheduleRepository.findById(processPassengerResponses.getScheduleId())
                .orElseThrow(() -> new DataNotFoundException(String.format("Schedule with id %s not found", processPassengerResponses.getScheduleId())));
        Passenger passenger = passengerRepository.findById(processPassengerResponses.getId())
                .orElseThrow(() -> new DataNotFoundException(String.format("Passenger with id %s not found", processPassengerResponses.getId())));

        BookingDetail bookingDetail = BookingDetail.builder()
                .id(String.format("bs-%s", UUID.randomUUID().toString()))
                .booking(booking)
                .passenger(passenger)
                .status(processPassengerResponses.getStatus())
                .schedule(schedule)
                .quantity(1)
                .aircraftPrice(schedule.getPrice())
                .bagagePricePer5kg(processPassengerResponses.getBaggageResponse().getBagagePricePer5kg())
                .checkInStatus(false)
                .extraBagage(processPassengerResponses.getBaggageResponse().getExtraBagage())
                .seatCode(processPassengerResponses.getSeatResponse().getSeatCode())
                .seatPrice(processPassengerResponses.getSeatResponse().getPrice().getAmount())
                .createdAt(LocalDateTime.now())
                .build();
        log.info("Do save data booking detail");
        bookingDetailRepository.save(bookingDetail);
        log.info("Success save data booking detail");
        return bookingDetail;
    }

    @Override
    public BigDecimal getTotalPayment(String bookingId) {
        log.info("Do calculate total amount");
        Booking booking = bookingRepository.findBookingDetailsById(bookingId);
        BigDecimal totalAmount = new BigDecimal(0);
        for (BookingDetail bookingDetail : booking.getBookingDetails()) {
            BigDecimal ticketPrice = bookingDetail.getAircraftPrice();
            BigDecimal seatPrice = bookingDetail.getSeatPrice();

            BigDecimal bagagePricePer5kg = bookingDetail.getBagagePricePer5kg();
            Integer extraBagage = bookingDetail.getExtraBagage();

            BigDecimal totalBagagePrice = BigDecimal.valueOf(((long) (extraBagage / 5) * bagagePricePer5kg.intValue()));

            totalAmount = totalAmount.add(ticketPrice);
            totalAmount = totalAmount.add(seatPrice);
            totalAmount = totalAmount.add(totalBagagePrice);

        }
        return totalAmount;
    }

    @Override
    public BookingResponse getBookingResponse(String bookingId) {
        log.info("get booking response");
        Booking booking = bookingRepository.findBookingDetailsById(bookingId);

        List<PassengerBookingResponse> departures = new ArrayList<>();
        List<PassengerBookingResponse> returns = new ArrayList<>();

        booking.getBookingDetails().stream().forEach(bookingDetail -> {
            if (bookingDetail.getStatus().equals("departure")) {
                Schedule schedule = scheduleRepository.findById(bookingDetail.getSchedule().getId())
                        .orElseThrow(() -> new DataNotFoundException("Schedule not found"));
                Bagage baggage = bagageRepository.findByAircraftId(schedule.getAircraft().getId());

                PassengerBookingResponse departureBookingResponse = PassengerBookingResponse.builder()
                        .passengerId(bookingDetail.getPassenger().getId())
                        .titel(bookingDetail.getPassenger().getTitel().getTitelName())
                        .ageCategory(bookingDetail.getPassenger().getAgeCategory().getCategoryName())
                        .firstName(bookingDetail.getPassenger().getFirstName())
                        .lastName(bookingDetail.getPassenger().getLastName())
                        .citizenship(bookingDetail.getPassenger().getCityzenship().getName())
                        .birthDate(bookingDetail.getPassenger().getBirthDate())
                        .passportNumber(bookingDetail.getPassenger().getPassportNumber())
                        .issuingCountry(bookingDetail.getPassenger().getIssuingCountry().getName())
                        .schedule(ScheduleResponse.builder()
                                .id(schedule.getId())
                                .originAirport(AirportResponse.builder()
                                        .iata(schedule.getOriginIataAirportCode().getIataAirportCode())
                                        .name(schedule.getOriginIataAirportCode().getName())
                                        .cityCode(schedule.getOriginIataAirportCode().getCity().getCodeId())
                                        .countryCode(schedule.getOriginIataAirportCode().getCity().getCountry().getCountryCode())
                                        .city(schedule.getOriginIataAirportCode().getCity().getName())
                                        .country(schedule.getOriginIataAirportCode().getCity().getCountry().getName())
                                        .createdAt(schedule.getOriginIataAirportCode().getCreatedAt())
                                        .build())
                                .destinationAirport(AirportResponse.builder()
                                        .iata(schedule.getDestIataAirportCode().getIataAirportCode())
                                        .name(schedule.getDestIataAirportCode().getName())
                                        .cityCode(schedule.getDestIataAirportCode().getCity().getCodeId())
                                        .countryCode(schedule.getDestIataAirportCode().getCity().getCountry().getCountryCode())
                                        .city(schedule.getDestIataAirportCode().getCity().getName())
                                        .country(schedule.getDestIataAirportCode().getCity().getCountry().getName())
                                        .createdAt(schedule.getDestIataAirportCode().getCreatedAt())
                                        .build())
                                .aircraft(AircraftResponse.builder()
                                        .id(schedule.getAircraft().getId())
                                        .type(schedule.getAircraft().getModel())
                                        .seatArrangement(schedule.getAircraft().getSeatArrangement())
                                        .distanceBetweenSeats(schedule.getAircraft().getDistanceBetweenSeats())
                                        .seatLengthUnit(schedule.getAircraft().getSeatLengthUnit())
                                        .build())
                                .price(PriceResponse.builder()
                                        .currencyCode(getIndonesiaCurrencyCode())
                                        .display(convertToDisplayCurrency(schedule.getPrice()))
                                        .amount(schedule.getPrice())
                                        .build())
                                .departureDate(schedule.getDepartureDate())
                                .arrivalDate(schedule.getArrivalDate())
                                .departureTime(schedule.getDepartureTime())
                                .arrivalTime(schedule.getArrivalTime())
                                .stock(schedule.getStock())
                                .createdAt(schedule.getCreatedAt())
                                .updatedAt(schedule.getUpdatedAt())
                                .build())
                        .aircraftSeat(AircraftSeatResponse.builder()
                                .seatCode(bookingDetail.getSeatCode())
                                .price(PriceResponse.builder()
                                        .currencyCode(getIndonesiaCurrencyCode())
                                        .display(convertToDisplayCurrency(bookingDetail.getSeatPrice()))
                                        .amount(bookingDetail.getSeatPrice())
                                        .build())
                                .build())
                        .bagage(BaggageBookingResponse.builder()
                                .bagagePricePer5kg(baggage.getBagagePricePer5kg())
                                .freeCabinCapacity(baggage.getFreeCabinBagageCapacity())
                                .freeBagageCapacity(baggage.getFreeBagageCapacity())
                                .price(PriceResponse.builder()
                                        .currencyCode(getIndonesiaCurrencyCode())
                                        .display(convertToDisplayCurrency(bookingDetail.getBagagePricePer5kg()))
                                        .amount(bookingDetail.getBagagePricePer5kg())
                                        .build())
                                .build())
                        .createdAt(bookingDetail.getCreatedAt())
                        .build();
                departures.add(departureBookingResponse);
            }else {
                Schedule schedule = scheduleRepository.findById(bookingDetail.getSchedule().getId())
                        .orElseThrow(() -> new DataNotFoundException("Schedule not found"));
                Bagage baggage = bagageRepository.findByAircraftId(schedule.getAircraft().getId());

                PassengerBookingResponse returnsBookingResponse = PassengerBookingResponse.builder()
                        .passengerId(bookingDetail.getPassenger().getId())
                        .titel(bookingDetail.getPassenger().getTitel().getTitelName())
                        .ageCategory(bookingDetail.getPassenger().getAgeCategory().getCategoryName())
                        .firstName(bookingDetail.getPassenger().getFirstName())
                        .lastName(bookingDetail.getPassenger().getLastName())
                        .citizenship(bookingDetail.getPassenger().getCityzenship().getName())
                        .birthDate(bookingDetail.getPassenger().getBirthDate())
                        .passportNumber(bookingDetail.getPassenger().getPassportNumber())
                        .issuingCountry(bookingDetail.getPassenger().getIssuingCountry().getName())
                        .schedule(ScheduleResponse.builder()
                                .id(schedule.getId())
                                .originAirport(AirportResponse.builder()
                                        .iata(schedule.getOriginIataAirportCode().getIataAirportCode())
                                        .name(schedule.getOriginIataAirportCode().getName())
                                        .cityCode(schedule.getOriginIataAirportCode().getCity().getCodeId())
                                        .countryCode(schedule.getOriginIataAirportCode().getCity().getCountry().getCountryCode())
                                        .city(schedule.getOriginIataAirportCode().getCity().getName())
                                        .country(schedule.getOriginIataAirportCode().getCity().getCountry().getName())
                                        .createdAt(schedule.getOriginIataAirportCode().getCreatedAt())
                                        .build())
                                .destinationAirport(AirportResponse.builder()
                                        .iata(schedule.getDestIataAirportCode().getIataAirportCode())
                                        .name(schedule.getDestIataAirportCode().getName())
                                        .cityCode(schedule.getDestIataAirportCode().getCity().getCodeId())
                                        .countryCode(schedule.getDestIataAirportCode().getCity().getCountry().getCountryCode())
                                        .city(schedule.getDestIataAirportCode().getCity().getName())
                                        .country(schedule.getDestIataAirportCode().getCity().getCountry().getName())
                                        .createdAt(schedule.getDestIataAirportCode().getCreatedAt())
                                        .build())
                                .aircraft(AircraftResponse.builder()
                                        .id(schedule.getAircraft().getId())
                                        .type(schedule.getAircraft().getModel())
                                        .seatArrangement(schedule.getAircraft().getSeatArrangement())
                                        .distanceBetweenSeats(schedule.getAircraft().getDistanceBetweenSeats())
                                        .seatLengthUnit(schedule.getAircraft().getSeatLengthUnit())
                                        .build())
                                .price(PriceResponse.builder()
                                        .currencyCode(getIndonesiaCurrencyCode())
                                        .display(convertToDisplayCurrency(schedule.getPrice()))
                                        .amount(schedule.getPrice())
                                        .build())
                                .departureDate(schedule.getDepartureDate())
                                .arrivalDate(schedule.getArrivalDate())
                                .departureTime(schedule.getDepartureTime())
                                .arrivalTime(schedule.getArrivalTime())
                                .stock(schedule.getStock())
                                .createdAt(schedule.getCreatedAt())
                                .updatedAt(schedule.getUpdatedAt())
                                .build())
                        .aircraftSeat(AircraftSeatResponse.builder()
                                .seatCode(bookingDetail.getSeatCode())
                                .price(PriceResponse.builder()
                                        .currencyCode(getIndonesiaCurrencyCode())
                                        .display(convertToDisplayCurrency(bookingDetail.getSeatPrice()))
                                        .amount(bookingDetail.getSeatPrice())
                                        .build())
                                .build())
                        .bagage(BaggageBookingResponse.builder()
                                .bagagePricePer5kg(baggage.getBagagePricePer5kg())
                                .freeCabinCapacity(baggage.getFreeCabinBagageCapacity())
                                .freeBagageCapacity(baggage.getFreeBagageCapacity())
                                .price(PriceResponse.builder()
                                        .currencyCode(getIndonesiaCurrencyCode())
                                        .display(convertToDisplayCurrency(bookingDetail.getBagagePricePer5kg()))
                                        .amount(bookingDetail.getBagagePricePer5kg())
                                        .build())
                                .build())
                        .createdAt(bookingDetail.getCreatedAt())
                        .build();
                returns.add(returnsBookingResponse);
            }
        });
        BigDecimal totalPayment = getTotalPayment(bookingId);
        log.info("Success get booking response");
        return BookingResponse.builder()
                .totalAmount(PriceResponse.builder()
                        .currencyCode(getIndonesiaCurrencyCode())
                        .display(convertToDisplayCurrency(totalPayment))
                        .amount(totalPayment)
                        .build())
                .bookingId(bookingId)
                .bookingReferenceNumber(booking.getBookingReferenceNumber())
                .departure(BookingDetailResponse.builder()
                        .data(departures)
                        .build())
                .returns(BookingDetailResponse.builder()
                        .data(returns)
                        .build())
                .build();
    }


    private String convertToDisplayCurrency(BigDecimal amount) {
        Locale indonesia = new Locale("id", "ID");
        NumberFormat numberFormat = NumberFormat.getCurrencyInstance(indonesia);

        String rupiah = numberFormat.format(amount.doubleValue());
        return rupiah;
    }

    private String getIndonesiaCurrencyCode() {
        Locale japan = new Locale("id", "ID");
        Currency currency = Currency.getInstance(japan);

        return currency.getCurrencyCode();
    }
}
