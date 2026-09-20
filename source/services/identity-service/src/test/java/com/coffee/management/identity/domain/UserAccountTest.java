package com.coffee.management.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.coffee.management.identity.domain.model.UserAccount;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserAccountTest {
    private static final Instant NOW=Instant.parse("2026-01-01T00:00:00Z");

    @Test void activeUnlockedAccountCanAuthenticate(){assertThat(user(UserAccount.Status.ACTIVE,null).canAuthenticate(NOW)).isTrue();}
    @Test void temporaryLockPreventsAuthentication(){assertThat(user(UserAccount.Status.ACTIVE,NOW.plusSeconds(60)).canAuthenticate(NOW)).isFalse();}
    @Test void expiredTemporaryLockAllowsAuthentication(){assertThat(user(UserAccount.Status.ACTIVE,NOW.minusSeconds(1)).canAuthenticate(NOW)).isTrue();}
    @Test void disabledAndLockedStatesCannotAuthenticate(){
        assertThat(user(UserAccount.Status.DISABLED,null).canAuthenticate(NOW)).isFalse();
        assertThat(user(UserAccount.Status.LOCKED,null).canAuthenticate(NOW)).isFalse();
    }
    private UserAccount user(UserAccount.Status status,Instant lockedUntil){return new UserAccount(UUID.randomUUID(),"user",null,"hash","User",status,0,lockedUntil,1);}
}
