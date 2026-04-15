package me.justindevb.replay.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ReplayAPITest {

    @Mock
    private ReplayManager mockManager;

    @BeforeEach
    void resetAPI() {
        // Ensure clean state
        try { ReplayAPI.shutdown(); } catch (Exception ignored) {}
    }

    @AfterEach
    void tearDown() {
        try { ReplayAPI.shutdown(); } catch (Exception ignored) {}
    }

    @Test
    void getBeforeInit_throws() {
        assertThrows(IllegalStateException.class, ReplayAPI::get);
    }

    @Test
    void initThenGet_returnsManager() {
        ReplayAPI.init(mockManager);
        assertSame(mockManager, ReplayAPI.get());
    }

    @Test
    void doubleInit_throws() {
        ReplayAPI.init(mockManager);
        assertThrows(IllegalStateException.class, () -> ReplayAPI.init(mockManager));
    }

    @Test
    void shutdownThenGet_throws() {
        ReplayAPI.init(mockManager);
        ReplayAPI.shutdown();
        assertThrows(IllegalStateException.class, ReplayAPI::get);
    }

    @Test
    void shutdownThenInitAgain_works() {
        ReplayAPI.init(mockManager);
        ReplayAPI.shutdown();
        ReplayAPI.init(mockManager);
        assertSame(mockManager, ReplayAPI.get());
    }
}
