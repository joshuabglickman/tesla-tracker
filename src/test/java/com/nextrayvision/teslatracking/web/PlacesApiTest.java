package com.nextrayvision.teslatracking.web;

import com.nextrayvision.teslatracking.model.OdometerReadingRepository;
import com.nextrayvision.teslatracking.model.VehicleRepository;
import com.nextrayvision.teslatracking.places.GeocodingService;
import com.nextrayvision.teslatracking.places.PlaceService;
import com.nextrayvision.teslatracking.places.SavedPlace;
import com.nextrayvision.teslatracking.places.SavedPlaceRepository;
import com.nextrayvision.teslatracking.tesla.KeyService;
import com.nextrayvision.teslatracking.tesla.TeslaAuthService;
import com.nextrayvision.teslatracking.tesla.TeslaProperties;
import com.nextrayvision.teslatracking.tracking.PollingService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlacesApiTest {

    private final SavedPlaceRepository savedPlaces = mock(SavedPlaceRepository.class);
    private final GeocodingService geocoding = mock(GeocodingService.class);
    private final ApiController api = new ApiController(mock(TeslaProperties.class), mock(TeslaAuthService.class),
            mock(KeyService.class), mock(PollingService.class), mock(VehicleRepository.class),
            mock(OdometerReadingRepository.class), mock(PlaceService.class), savedPlaces, geocoding);

    PlacesApiTest() {
        when(savedPlaces.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void addingByAddressPutsThePlaceWhereTheAddressIs() {
        when(geocoding.findPosition("1020 W 41st St, Baltimore, MD")).thenReturn(Optional.of(new double[]{39.3384, -76.6353}));

        var response = api.addPlace(new ApiController.NewPlace(" Giant ", "1020 W 41st St, Baltimore, MD", null, null, null));

        SavedPlace saved = (SavedPlace) response.getBody();
        assertThat(saved.getName()).isEqualTo("Giant");
        assertThat(saved.getLatitude()).isEqualTo(39.3384);
        assertThat(saved.getRadiusMeters()).isEqualTo(150);
    }

    @Test
    void anAddressThatIsNotOnTheMapIsRejected() {
        when(geocoding.findPosition(any())).thenReturn(Optional.empty());

        assertThat(api.addPlace(new ApiController.NewPlace("Nowhere", "nowhere at all", null, null, null))
                .getStatusCode().value()).isEqualTo(400);
        verify(savedPlaces, never()).save(any());
    }

    @Test
    void editingChangesNameAddressAndRadiusButNotThePosition() {
        SavedPlace marina = new SavedPlace("Marina", null, 39.28078, -76.581492, 150);
        when(savedPlaces.findById(2L)).thenReturn(Optional.of(marina));

        api.updatePlace(2, new ApiController.NewPlace("Anchorage Marina", "2401 Boston St, Baltimore, MD 21224", 1.0, 1.0, 300.0));

        assertThat(marina.getName()).isEqualTo("Anchorage Marina");
        assertThat(marina.getAddress()).isEqualTo("2401 Boston St, Baltimore, MD 21224");
        assertThat(marina.getRadiusMeters()).isEqualTo(300);
        assertThat(marina.getLatitude()).isEqualTo(39.28078);
        assertThat(api.updatePlace(2, new ApiController.NewPlace("Marina", null, null, null, 1.0))
                .getStatusCode().value()).isEqualTo(400);
    }
}
