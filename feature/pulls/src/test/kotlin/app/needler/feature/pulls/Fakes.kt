package app.needler.feature.pulls

import app.needler.core.domain.model.AlbumRequest
import app.needler.core.domain.model.BatchRequestReceipt
import app.needler.core.domain.model.CertificateInfo
import app.needler.core.domain.model.ConnectivityState
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.PlayerOnlyReason
import app.needler.core.domain.model.Pull
import app.needler.core.domain.model.PullActivitySummary
import app.needler.core.domain.model.PullBucket
import app.needler.core.domain.model.PullTaskId
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.RequestHistoryPage
import app.needler.core.domain.model.RequestOutcome
import app.needler.core.domain.model.RequestReceipt
import app.needler.core.domain.model.ServerCapabilities
import app.needler.core.domain.model.ServerProbe
import app.needler.core.domain.model.SessionState
import app.needler.core.domain.model.TrackRequest
import app.needler.core.domain.model.User
import app.needler.core.domain.model.WantedList
import app.needler.core.domain.repository.PullRepository
import app.needler.core.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Hand-written fakes rather than mocks.
 *
 * `PullsViewModel` is mostly a reconciliation — the task list and the parked
 * approvals folded into one ordered sequence — and that is a property of the
 * *data*, not of which methods were called. A fake whose flows can be pushed
 * new values mid-test is the only way to assert on it; a mock would only report
 * that `observePulls()` was invoked once, which is not the thing that can be
 * wrong.
 *
 * The write methods do record their arguments, because for those the question
 * genuinely is which endpoint the ViewModel chose: REQUIREMENTS.md keeps the
 * download lane and the request lane deliberately separate, and picking the
 * wrong one is silent.
 */
internal class FakePullRepository(
    pulls: List<Pull> = emptyList(),
    approvals: List<Pull> = emptyList(),
    summary: PullActivitySummary? = null,
    badgeCount: Int = 0,
) : PullRepository {

    val pullsFlow: MutableStateFlow<List<Pull>> = MutableStateFlow(pulls)
    val approvalsFlow: MutableStateFlow<List<Pull>> = MutableStateFlow(approvals)
    val summaryFlow: MutableStateFlow<PullActivitySummary?> = MutableStateFlow(summary)
    val badgeFlow: MutableStateFlow<Int> = MutableStateFlow(badgeCount)

    var cancelTaskOutcome: Outcome<Unit> = Outcome.Ok
    var retryTaskOutcome: Outcome<Unit> = Outcome.Ok
    var cancelRequestOutcome: Outcome<Unit> = Outcome.Ok
    var retryRequestOutcome: Outcome<Unit> = Outcome.Ok

    val cancelledTasks: MutableList<PullTaskId> = mutableListOf()
    val retriedTasks: MutableList<PullTaskId> = mutableListOf()
    val cancelledRequests: MutableList<ReleaseGroupMbid> = mutableListOf()
    val retriedRequests: MutableList<ReleaseGroupMbid> = mutableListOf()
    val markedSeen: MutableList<Set<ReleaseGroupMbid>> = mutableListOf()

    var refreshCount: Int = 0

    /**
     * How many collectors took the polling flow rather than the plain one.
     *
     * The one thing about REQUIREMENTS.md "Polling schedule" that is testable from this module: the
     * 2 s loop itself lives in `:core:data` and is tested there, but *which* flow the screen
     * subscribes to is this module's choice, and taking the unpolled one would leave the screen
     * static with nothing failing.
     */
    var liveSubscriptions: Int = 0

    override fun observePulls(): Flow<List<Pull>> = pullsFlow

    override fun observePullsLive(): Flow<List<Pull>> = pullsFlow.onStart { liveSubscriptions++ }

    override fun observePulls(bucket: PullBucket): Flow<List<Pull>> =
        pullsFlow.map { list -> list.filter { it.bucket == bucket } }

    override fun observePull(mbid: ReleaseGroupMbid): Flow<Pull?> =
        pullsFlow.map { list -> list.firstOrNull { it.releaseGroupMbid == mbid } }

    override fun observePendingApprovals(): Flow<List<Pull>> = approvalsFlow

    override fun observeActivitySummary(): Flow<PullActivitySummary?> = summaryFlow

    override fun observePullBadgeCount(): Flow<Int> = badgeFlow

    override suspend fun requestAlbum(request: AlbumRequest): Outcome<RequestReceipt> =
        error("the Pulls screen places no requests; see PullsScreen's KDoc")

    override suspend fun requestTrack(request: TrackRequest): Outcome<RequestReceipt> =
        error("the Pulls screen places no requests; see PullsScreen's KDoc")

    override suspend fun requestAlbums(
        requests: List<AlbumRequest>,
    ): Outcome<BatchRequestReceipt> =
        error("the Pulls screen places no requests; see PullsScreen's KDoc")

    override suspend fun cancelRequest(mbid: ReleaseGroupMbid): Outcome<Unit> {
        cancelledRequests += mbid
        return cancelRequestOutcome
    }

    override suspend fun retryRequest(mbid: ReleaseGroupMbid): Outcome<Unit> {
        retriedRequests += mbid
        return retryRequestOutcome
    }

    override suspend fun cancelTask(taskId: PullTaskId): Outcome<Unit> {
        cancelledTasks += taskId
        return cancelTaskOutcome
    }

    override suspend fun retryTask(taskId: PullTaskId): Outcome<Unit> {
        retriedTasks += taskId
        return retryTaskOutcome
    }

    override suspend fun refreshPulls(): Outcome<Unit> {
        refreshCount++
        return Outcome.Ok
    }

    override suspend fun refreshActivitySummary(): Outcome<PullActivitySummary> =
        error("the activity-summary poll belongs to :core:data, not to this screen")

    override suspend fun markCompletionsSeen(mbids: Set<ReleaseGroupMbid>) {
        markedSeen += mbids
    }

    // ---- the two read-through lanes -----------------------------------------
    //
    // These two are functions rather than fields of canned data, because what the tests need to
    // assert is a *sequence*: page one then page two, or a failure then a success after a retry.
    // A single `Outcome` field could not express either, and the thing most worth testing about
    // this lane is that the ViewModel asks for the right page each time.

    /** Every `page` argument `requestHistory` was called with, in order. */
    val historyRequests: MutableList<Int> = mutableListOf()

    /** Every `page_size` argument, so a test can pin that the endpoint's own default is used. */
    val historyPageSizes: MutableList<Int> = mutableListOf()

    /** How many times `wantedList` was called. The lane has no paging, so a page number is not it. */
    var wantedCalls: Int = 0

    /** What the server answers for a given page. Keyed on the page so a walk can be staged. */
    var historyAnswer: (Int) -> Outcome<RequestHistoryPage> = { page ->
        Outcome.Success(RequestHistoryPage.Empty.copy(page = page))
    }

    var wantedAnswer: () -> Outcome<WantedList> = { Outcome.Success(WantedList.Empty) }

    override suspend fun requestHistory(
        page: Int,
        pageSize: Int,
        status: RequestOutcome?,
        newestFirst: Boolean,
    ): Outcome<RequestHistoryPage> {
        historyRequests += page
        historyPageSizes += pageSize
        return historyAnswer(page)
    }

    override suspend fun wantedList(): Outcome<WantedList> {
        wantedCalls++
        return wantedAnswer()
    }
}

/**
 * A repository from before the two read-through lanes existed.
 *
 * Every abstract member of `PullRepository` is forwarded to [FakePullRepository]; `requestHistory`
 * and `wantedList` are **deliberately not overridden**, so calls to them take the interface's own
 * bodies — which answer `NeedlerError.CapabilityUnavailable`. That failure, rather than an empty
 * answer, is what the interface's KDoc says it is for: "a screen must never mistake 'nobody
 * implemented this' for 'you have never asked for anything'". This class is how a test can check
 * that the screen does not make that mistake.
 *
 * Written out by hand rather than with `by`. Kotlin's delegation generates an override for *every*
 * member the delegate provides, including the two with defaults, so a delegating class would quietly
 * take the fake's answers and the branch under test would never run.
 *
 * It is also a standing check on the additivity argument. The KDoc on those two members says making
 * them abstract "is the better end state, and turning these two abstract once the fakes have caught
 * up is a one-line change per file" — on the day that happens, this class stops compiling, which is
 * exactly the reminder that is wanted.
 */
internal class NoLanesRepository(
    private val delegate: FakePullRepository = FakePullRepository(),
) : PullRepository {

    override fun observePulls(): Flow<List<Pull>> = delegate.observePulls()

    override fun observePullsLive(): Flow<List<Pull>> = delegate.observePullsLive()

    override fun observePulls(bucket: PullBucket): Flow<List<Pull>> = delegate.observePulls(bucket)

    override fun observePull(mbid: ReleaseGroupMbid): Flow<Pull?> = delegate.observePull(mbid)

    override fun observePendingApprovals(): Flow<List<Pull>> = delegate.observePendingApprovals()

    override fun observeActivitySummary(): Flow<PullActivitySummary?> =
        delegate.observeActivitySummary()

    override fun observePullBadgeCount(): Flow<Int> = delegate.observePullBadgeCount()

    override suspend fun requestAlbum(request: AlbumRequest): Outcome<RequestReceipt> =
        delegate.requestAlbum(request)

    override suspend fun requestTrack(request: TrackRequest): Outcome<RequestReceipt> =
        delegate.requestTrack(request)

    override suspend fun requestAlbums(
        requests: List<AlbumRequest>,
    ): Outcome<BatchRequestReceipt> = delegate.requestAlbums(requests)

    override suspend fun cancelRequest(mbid: ReleaseGroupMbid): Outcome<Unit> =
        delegate.cancelRequest(mbid)

    override suspend fun retryRequest(mbid: ReleaseGroupMbid): Outcome<Unit> =
        delegate.retryRequest(mbid)

    override suspend fun cancelTask(taskId: PullTaskId): Outcome<Unit> = delegate.cancelTask(taskId)

    override suspend fun retryTask(taskId: PullTaskId): Outcome<Unit> = delegate.retryTask(taskId)

    override suspend fun refreshPulls(): Outcome<Unit> = delegate.refreshPulls()

    override suspend fun refreshActivitySummary(): Outcome<PullActivitySummary> =
        delegate.refreshActivitySummary()

    override suspend fun markCompletionsSeen(mbids: Set<ReleaseGroupMbid>) =
        delegate.markCompletionsSeen(mbids)
}

/**
 * A session that only answers the one question this screen asks.
 *
 * Everything else throws rather than returning a plausible default: a fake that
 * quietly answered `probeServer` would let a test pass while the ViewModel was
 * doing something it has no business doing.
 */
internal class FakeSessions(
    connectivity: ConnectivityState = ConnectivityState.Unmetered,
) : SessionRepository {

    val connectivityFlow: MutableStateFlow<ConnectivityState> = MutableStateFlow(connectivity)

    override fun observeConnectivity(): Flow<ConnectivityState> = connectivityFlow

    override suspend fun currentConnectivity(): ConnectivityState = connectivityFlow.value

    override fun observeSession(): Flow<SessionState> = error("not used by :feature:pulls")

    override suspend fun currentSession(): SessionState = error("not used by :feature:pulls")

    override fun observeCapabilities(): Flow<ServerCapabilities?> =
        error("not used by :feature:pulls")

    override suspend fun currentCapabilities(): ServerCapabilities? =
        error("not used by :feature:pulls")

    override suspend fun probeServer(rawUrl: String): Outcome<ServerProbe> =
        error("not used by :feature:pulls")

    override suspend fun trustCertificate(certificate: CertificateInfo): Outcome<Unit> =
        error("not used by :feature:pulls")

    override suspend fun connect(
        serverUrl: String,
        username: String,
        password: String,
        deviceName: String,
    ): Outcome<SessionState> = error("not used by :feature:pulls")

    override suspend fun negotiateCapabilities(): Outcome<ServerCapabilities> =
        error("not used by :feature:pulls")

    override suspend fun refreshUser(): Outcome<User> = error("not used by :feature:pulls")

    override suspend fun reauthenticate(password: String): Outcome<SessionState> =
        error("not used by :feature:pulls")

    override suspend fun markSessionStale(reason: PlayerOnlyReason) = Unit

    override suspend fun markAppPasswordRejected(subsonicErrorCode: Int?): SessionState =
        error("not used by :feature:pulls")

    override suspend fun repairAppPassword(): Outcome<SessionState> =
        error("not used by :feature:pulls")

    override suspend fun signOut(revokeRemote: Boolean): Outcome<Unit> =
        error("not used by :feature:pulls")
}
