import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

// One slot shared by every service test task (events.service). Core's standalone build ran the service tests one task at
// a time (no parallel, no configuration cache); the root build turns both on, which would run test and perfTest side by
// side and break the latency and shutdown budgets they assert.
abstract class ServiceTestSlot : BuildService<BuildServiceParameters.None>
