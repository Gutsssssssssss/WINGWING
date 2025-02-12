package com.ssafy.shieldroneapp.ui.map

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.MapView
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.KakaoMapReadyCallback
import com.ssafy.shieldroneapp.data.model.DroneStatus
import com.ssafy.shieldroneapp.data.model.LocationType
import com.ssafy.shieldroneapp.data.model.WatchConnectionState
import com.ssafy.shieldroneapp.data.model.request.DroneCancelRequest
import com.ssafy.shieldroneapp.data.model.request.DroneMatchRequest
import com.ssafy.shieldroneapp.data.source.remote.SafetyMessageSender
import com.ssafy.shieldroneapp.services.base.BaseMobileService
import com.ssafy.shieldroneapp.services.connection.WearableDataListenerService
import com.ssafy.shieldroneapp.permissions.PermissionViewModel
import com.ssafy.shieldroneapp.services.sensor.AudioRecordService
import com.ssafy.shieldroneapp.ui.components.AlertModal
import com.ssafy.shieldroneapp.ui.components.AlertType
import com.ssafy.shieldroneapp.ui.components.ConnectionStatusSnackbar
import com.ssafy.shieldroneapp.ui.components.HeartRateDisplay
import com.ssafy.shieldroneapp.ui.components.WatchConnectionManager
import com.ssafy.shieldroneapp.ui.map.screens.AlertHandler
import com.ssafy.shieldroneapp.ui.map.screens.DroneAnimation
import com.ssafy.shieldroneapp.ui.map.screens.DroneAssignmentFailureModal
import com.ssafy.shieldroneapp.ui.map.screens.DroneAssignmentSuccessModal
import com.ssafy.shieldroneapp.ui.map.screens.MapMarkerInfoModal
import com.ssafy.shieldroneapp.ui.map.screens.SearchInputFields
import com.ssafy.shieldroneapp.ui.map.screens.SearchResultsModal
import com.ssafy.shieldroneapp.utils.rememberKeyboardController
import com.ssafy.shieldroneapp.utils.setupMap
import com.ssafy.shieldroneapp.utils.updateAllMarkers
import com.ssafy.shieldroneapp.viewmodels.HeartRateViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 사용자의 현재 위치와 드론 경로 안내를 제공하는 메인 Map 화면.
 *
 * 유저의 GPS 위치를 받아 점으로 표시하고, 근처 드론 정류장을 하늘색 마커로 표시.
 * 출발지/도착지 입력, 드론 배정 요청, 경로 안내, 위험 상황 알림 등 주요 기능을 관리한다.
 * 드론 배정 성공 시 경로를 표시하고, 드론 코드 인식 화면으로 이동한다.
 *
 * @property viewModel Map 화면의 상태와 로직을 관리하는 ViewModel
 */

private const val TAG = "모바일: 맵 화면"

@Composable
fun MapScreen(
    viewModel: HeartRateViewModel = hiltViewModel(),
    alertHandler: AlertHandler,
    safetyMessageSender: SafetyMessageSender,
    mapViewModel: MapViewModel = hiltViewModel(),
    permissionViewModel: PermissionViewModel = hiltViewModel(),
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
) {
    val state = mapViewModel.state.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    val audioRecordingServiceIntent = remember { Intent(context, AudioRecordService::class.java) }

    // 카카오 맵
    val kakaoMap = remember { mutableStateOf<KakaoMap?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val isMapInitialized = remember { mutableStateOf(false) }

    // 위치 서비스(GPS, 네트워크) 활성화 상태를 실시간으로 감지
    val locationServicesEnabled = mapViewModel.locationServicesEnabled.collectAsStateWithLifecycle()

    // 화면 회전 감지를 위한 Configuration 변경 감지
    val configuration = LocalConfiguration.current
    val screenRotation = remember { mutableIntStateOf(configuration.orientation) }

    // 키보드 매니저 생성 (맵 클릭 시, 키보드 숨기기 위해)
    val keyboardController = rememberKeyboardController()

    // 알람
    val heartRate by remember(viewModel) {
        viewModel.heartRateData
    }.collectAsStateWithLifecycle()
    val connectionState = viewModel.watchConnectionState.collectAsStateWithLifecycle().value
    val alertState = mapViewModel.alertState.collectAsStateWithLifecycle().value

    // 워치 연결 관리
    WatchConnectionManager(
        onConnectionStatusDetermined = { isConnected ->
            viewModel.updateWatchConnectionState(
                if (isConnected) WatchConnectionState.Connected
                else WatchConnectionState.Disconnected
            )
        }
    )

    LaunchedEffect(Unit) {
        // 맵 화면 진입 시 데이터 수신 활성화
        Intent(context, WearableDataListenerService::class.java).apply {
            action = BaseMobileService.ACTION_ENABLE_DATA_LISTENING
            context.startService(this)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // 맵 화면 종료 시 데이터 수신 비활성화
            Intent(context, WearableDataListenerService::class.java).apply {
                action = BaseMobileService.ACTION_DISABLE_DATA_LISTENING
                context.startService(this)
            }
        }
    }

    // 위치 서비스 상태 체크
    LaunchedEffect(Unit) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isLocationEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        mapViewModel.handleEvent(MapEvent.UpdateLocationServicesState(isLocationEnabled))
    }

    // 권한 요청 상태 관리
    val (showLocationPermissionDialog, setShowLocationPermissionDialog) = remember {
        mutableStateOf(
            false
        )
    }
    val (showAudioPermissionDialog, setShowAudioPermissionDialog) = remember { mutableStateOf(false) }

    // 음성 권한 요청 launcher
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        permissionViewModel.updateAudioPermissionStatus(isGranted)
        if (isGranted) {
            // 권한이 허용되면 오디오 녹음 서비스 시작
            audioRecordingServiceIntent.action = AudioRecordService.ACTION_START_RECORDING
            context.startForegroundService(audioRecordingServiceIntent)
        } else {
            setShowAudioPermissionDialog(true)
        }
    }

    // 위치 권한 요청 launcher
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (locationGranted) {
            mapViewModel.handleEvent(MapEvent.LoadCurrentLocationAndFetchHives)
            mapViewModel.handleEvent(MapEvent.StartLocationTracking)
            // 위치 권한이 허용되면 음성 권한 요청
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            setShowLocationPermissionDialog(true)
        }
    }

    // 초기 위치 권한 요청
    LaunchedEffect(Unit) {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    // 위치 권한 거부 시 다이얼로그
    if (showLocationPermissionDialog) {
        AlertDialog(
            onDismissRequest = { setShowLocationPermissionDialog(false) },
            title = { Text("위치 권한 필요") },
            text = { Text("앱 사용을 위해 위치 권한이 필요합니다.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                        setShowLocationPermissionDialog(false)
                    }
                ) {
                    Text("권한 허용")
                }
            },
            dismissButton = {
                TextButton(onClick = { setShowLocationPermissionDialog(false) }) {
                    Text("취소")
                }
            }
        )
    }

// 음성 권한 거부 시 다이얼로그
    if (showAudioPermissionDialog) {
        AlertDialog(
            onDismissRequest = { setShowAudioPermissionDialog(false) },
            title = { Text("음성 권한 필요") },
            text = { Text("앱 사용을 위해 음성 권한이 필요합니다.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        setShowAudioPermissionDialog(false)
                    }
                ) {
                    Text("권한 허용")
                }
            },
            dismissButton = {
                TextButton(onClick = { setShowAudioPermissionDialog(false) }) {
                    Text("취소")
                }
            }
        )
    }

    // 위치 갱신 시, 지도 초기화 및 마커 업데이트
    LaunchedEffect(state.currentLocation, state.isTrackingLocation, state.nearbyHives) {
        kakaoMap.value?.let { map ->
            if (!isMapInitialized.value) {
                setupMap(map, mapViewModel)
                isMapInitialized.value = true
                Log.d(TAG, "지도 초기 설정 완료")
            }

            // 현재 위치나 주변 정류장 정보가 있을 때 마커 업데이트
            if (state.currentLocation != null || state.nearbyHives.isNotEmpty()) {
                updateAllMarkers(map, state)
                Log.d(
                    TAG,
                    "마커 업데이트 - 현재 위치: ${state.currentLocation}, 정류장 수: ${state.nearbyHives.size}"
                )
            }
        }
    }

    // 화면 회전 시 마커 재생성을 위한 Effect
    LaunchedEffect(configuration.orientation) {
        if (screenRotation.value != configuration.orientation) {
            screenRotation.value = configuration.orientation
            kakaoMap.value?.let { map ->
                if (state.currentLocation != null) {
                    updateAllMarkers(map, state)
                    Log.d("MapScreen", "화면 회전으로 인한 마커 재생성")
                }
            }
        }
    }

    // 위치 서비스가 비활성화된 경우 다이얼로그 표시
    if (!locationServicesEnabled.value) {
        AlertDialog(
            onDismissRequest = {
                mapViewModel.handleEvent(MapEvent.UpdateLocationServicesState(true))
            },
            title = { Text("위치 서비스 필요") },
            text = { Text("이 앱을 사용하기 위해서는 위치 서비스가 필요합니다.\n설정에서 위치 서비스를 활성화해주세요.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                        context.startActivity(intent)
                        mapViewModel.handleEvent(MapEvent.UpdateLocationServicesState(true))
                    },
                    colors = androidx.compose.material.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colors.secondary // 버튼 텍스트 색상을 secondary로 설정
                    )
                ) {
                    Text("설정으로 이동")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        mapViewModel.handleEvent(MapEvent.UpdateLocationServicesState(true))
                    },
                    colors = androidx.compose.material.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colors.secondary // 버튼 텍스트 색상을 secondary로 설정
                    )
                ) {
                    Text("취소")
                }
            }
        )
    }

    // 지도 영역
    val mapView = remember { MapView(context) } // 기존 MapView

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.start(
                    object : MapLifeCycleCallback() {
                        override fun onMapDestroy() {
                            Log.d("MapScreen", "Map destroyed")
                        }

                        override fun onMapError(error: Exception) {
                            Log.e("MapScreen", "Map error: ${error.message}", error)
                        }
                    },
                    object : KakaoMapReadyCallback() {
                        override fun onMapReady(map: KakaoMap) {
                            Log.d("MapScreen", "Map ready")
                            kakaoMap.value = map
                            if (state.currentLocation != null) {
                                setupMap(map, mapViewModel)
                                isMapInitialized.value = true
                                updateAllMarkers(map, state)  // 명시적으로 마커 업데이트 호출
                            }
                        }
                    }
                )

                Lifecycle.Event.ON_RESUME -> {
                    // 화면이 다시 활성화될 때 마커 상태 복원
                    kakaoMap.value?.let { map ->
                        if (state.currentLocation != null) {
                            updateAllMarkers(map, state)
                            Log.d("MapScreen", "화면 재개로 인한 마커 재생성")
                        }
                    }
                }

                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            kakaoMap.value = null
            isMapInitialized.value = false
        }
    }

    Box(
        modifier = Modifier.fillMaxWidth()
    ) {
        // 1. AndroidView로 MapView를 전체 화면에 배치 (최하단 레이어)
        AndroidView(
            factory = { mapView },
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    keyboardController.hideKeyboard()
                    // 모달 닫기 처리
                    if (state.showSearchResultsModal ||
                        state.showStartMarkerModal ||
                        state.showEndMarkerModal ||
                        state.showDroneMatchResultModal ||
                        state.showDroneAssignmentSuccessModal ||
                        state.showDroneAssignmentFailureModal ||
                        state.showCancelSuccessModal
                    ) {
                        mapViewModel.handleEvent(MapEvent.CloseAllModals)
                    }
                },
        )

        // 2. UI 요소들 (맵 위의 레이어)

        // 2-1) 검색 필드 & 마커 정보 모달
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // 검색 필드
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                SearchInputFields(
                    startText = state.startSearchText,
                    endText = state.endSearchText,
                    onStartTextChange = {
                        mapViewModel.handleEvent(
                            MapEvent.UpdateStartLocationText(
                                it
                            )
                        )
                    },
                    onEndTextChange = { mapViewModel.handleEvent(MapEvent.UpdateEndLocationText(it)) },
                    onFieldClick = { type ->
                        mapViewModel.handleEvent(
                            MapEvent.SearchFieldClicked(
                                type
                            )
                        )
                    }

                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 마커 정보 모달 - 출발지
            if (state.showStartMarkerModal && state.selectedStartMarker != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            mapViewModel.handleEvent(MapEvent.CloseAllModals)
                        },
                    contentAlignment = Alignment.TopCenter
                ) {
                    Box(
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { /* 모달 안쪽은 클릭해도 닫히지 않도록 */ }
                    ) {
                        MapMarkerInfoModal(
                            routeLocation = state.selectedStartMarker,
                            onSelect = {
                                mapViewModel.handleEvent(MapEvent.SetStartLocation(state.selectedStartMarker))
                                mapViewModel.handleEvent(MapEvent.CloseAllModals)
                            }
                        )
                    }
                }
            }
        }

        // 2-2) 드론 관련 버튼 - 배정 취소/배정 요청/배정 성공 모달
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
        ) {
            // [버튼] 드론 배정 취소
            if (state.droneState?.matchStatus == DroneStatus.MATCHING_ASSIGNED) {
                Text(
                    text = "드론 배정 취소",
                    modifier = Modifier
                        .padding(end = 16.dp, bottom = 72.dp)
                        .align(Alignment.BottomEnd) // 하단 오른쪽에 배치
                        .clickable {
                            state.droneState.droneId.let { droneId ->
                                mapViewModel.handleEvent(
                                    MapEvent.RequestDroneCancel(
                                        DroneCancelRequest(droneId = droneId)
                                    )
                                )
                                mapViewModel.handleEvent(MapEvent.ClearDroneState) // 드론 상태 초기화
                                mapViewModel.handleEvent(MapEvent.ClearLocationData) // 출발/도착지 정보 초기화
                            }
                        },
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f), // 회색 텍스트
                    style = MaterialTheme.typography.subtitle2.copy(textDecoration = TextDecoration.Underline) // 밑줄
                )
            }

            if (state.droneState == null || state.droneState.matchStatus == DroneStatus.MATCHING_NONE) {
                // [버튼] 드론 배정 요청
                Button(
                    onClick = { mapViewModel.handleEvent(MapEvent.RequestDroneAssignment) },
                    shape = RoundedCornerShape(0.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .align(Alignment.BottomCenter),
                    enabled = (state.selectedStart != null && state.selectedEnd != null),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = MaterialTheme.colors.secondary,
                        contentColor = MaterialTheme.colors.onSecondary,
                    ),
                ) {
                    Text(
                        text = "드론 배정 요청",
                        style = MaterialTheme.typography.h5,
                        color = MaterialTheme.colors.onSecondary,
                    )
                }
            } else if (state.droneState.matchStatus == DroneStatus.MATCHING_ASSIGNED) {
                // [버튼] 드론 배정 성공 모달 열기
                Button(
                    onClick = {
                        mapViewModel.handleEvent(
                            MapEvent.HandleDroneMatchingResult(
                                DroneStatus.MATCHING_ASSIGNED
                            )
                        )
                    },
                    shape = RoundedCornerShape(0.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .align(Alignment.BottomCenter),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = MaterialTheme.colors.secondary,
                        contentColor = MaterialTheme.colors.onSecondary,
                    ),
                ) {
                    Text(
                        text = "최종 매칭 요청",
                        style = MaterialTheme.typography.h5,
                        color = MaterialTheme.colors.onSecondary,
                    )
                }
            } else if (state.droneState.matchStatus == DroneStatus.MATCHING_COMPLETE) {
                // [버튼] 서비스 종료
                Button(
                    onClick = {
                        state.droneState.droneId.let { droneId ->
                            mapViewModel.handleEvent(
                                MapEvent.RequestServiceEnd(
                                    DroneCancelRequest(droneId = droneId)
                                )
                            ) // 서비스 종료
                            mapViewModel.handleEvent(MapEvent.ClearDroneState) // 드론 상태 초기화
                            mapViewModel.handleEvent(MapEvent.ClearLocationData) // 출발/도착지 정보 초기화
                        }
                    },
                    shape = RoundedCornerShape(0.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .align(Alignment.BottomCenter),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = MaterialTheme.colors.secondary,
                        contentColor = MaterialTheme.colors.onSecondary,
                    ),
                ) {
                    Text(
                        text = "서비스 종료",
                        style = MaterialTheme.typography.h5,
                        color = MaterialTheme.colors.onSecondary,
                    )
                }


                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .align(Alignment.BottomCenter)
                        .background(MaterialTheme.colors.secondary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "서비스 종료",
                        style = MaterialTheme.typography.h5,
                        color = MaterialTheme.colors.onSecondary // 텍스트 색상
                    )
                }

            }

        }

        // 3. 최상단 레이어

        // 3-1) 검색 결과 모달
        if (state.showSearchResultsModal) {
            SearchResultsModal(
                searchType = state.searchType,
                searchResults = state.searchResults,
                onItemSelected = { selectedLocation ->
                    if (state.searchType == LocationType.START) {
                        mapViewModel.handleEvent(MapEvent.SetStartLocation(selectedLocation))
                    } else {
                        mapViewModel.handleEvent(MapEvent.SetEndLocation(selectedLocation))
                    }
                    mapViewModel.handleEvent(MapEvent.CloseAllModals)
                    keyboardController.hideKeyboard()
                },
                onDismiss = { mapViewModel.handleEvent(MapEvent.CloseAllModals) }
            )
        }

        // 3-2) 드론 배정 성공 결과 모달
        if (state.showDroneAssignmentSuccessModal && state.droneState != null) {
            DroneAssignmentSuccessModal(
                droneState = state.droneState,
                selectedStart = state.selectedStart?.locationName ?: "",
                selectedEnd = state.selectedEnd?.locationName ?: "",
                onDroneCodeInput = { code ->
                    mapViewModel.handleEvent(
                        MapEvent.RequestDroneMatching(
                            DroneMatchRequest(
                                droneId = state.droneState.droneId,
                                droneCode = code
                            )
                        )
                    )
                },
                onRequestMatching = { code ->
                    mapViewModel.handleEvent(
                        MapEvent.RequestDroneMatching(
                            DroneMatchRequest(
                                droneId = state.droneState.droneId,
                                droneCode = code
                            )
                        )
                    )
                },
                onDismiss = { mapViewModel.handleEvent(MapEvent.CloseAllModals) },
            )
        }

        // 3-3) 드론 배정 실패 결과 모달
        if (state.showDroneAssignmentFailureModal && state.droneAssignmentError != null) {
            DroneAssignmentFailureModal(
                errorMessage = state.droneAssignmentError,
                onDismiss = { mapViewModel.handleEvent(MapEvent.CloseAllModals) }
                // TODO: 이거 아래 꺼 동작 확인
//                onDismiss = { mapViewModel.handleEvent(MapEvent.CloseModal(ModalType.DRONE_MATCH_RESULT)) }
            )
        }

        // 3-4) 드론 배정 취소 결과 모달
        if (state.showCancelSuccessModal) {
            AlertDialog(
                onDismissRequest = {
                    mapViewModel.handleEvent(MapEvent.CloseAllModals)
                },
                title = {
                    Text(
                        "드론 배정 취소",
                        style = MaterialTheme.typography.subtitle1,
                    )
                },
                text = {
                    Text(
                        text = "드론 배정이 성공적으로 취소되었습니다.",
                        style = MaterialTheme.typography.body1,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            mapViewModel.handleEvent(MapEvent.CloseAllModals)
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colors.secondary
                        ),
                    ) {
                        Text(
                            text = "확인",
                            style = MaterialTheme.typography.subtitle2,
                        )
                    }
                }
            )
        }

        // 3-5) 드론 최종 매칭 결과 모달
        if (state.showDroneMatchResultModal) {
            AlertDialog(
                onDismissRequest = { mapViewModel.handleEvent(MapEvent.CloseModal(ModalType.DRONE_MATCH_RESULT)) },
                title = {
                    Text(
                        text = if (state.error == null) "매칭 성공" else "매칭 실패",
                        style = MaterialTheme.typography.subtitle1
                    )
                },
                text = {
                    Text(
                        text = state.error
                            ?: "드론 매칭이 성공적으로 완료되었습니다!\n\n드론이 확인할 수 있도록 5초간 머리 위로 손을 들어주세요!",
                        style = MaterialTheme.typography.body1
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (state.error == null) {
                                mapViewModel.handleEvent(MapEvent.CloseModal(ModalType.DRONE_MATCH_RESULT))
                                mapViewModel.handleEvent(MapEvent.CloseModal(ModalType.DRONE_ASSIGNMENT_SUCCESS))
                            } else {
                                mapViewModel.handleEvent(MapEvent.CloseModal(ModalType.DRONE_MATCH_RESULT))
                            }
                        },
                        colors = androidx.compose.material.ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colors.secondary
                        )
                    ) {
                        Text(
                            text = "확인",
                            style = MaterialTheme.typography.subtitle2,
                        )
                    }
                }
            )
        }

        // 3-6) 드론 애니메이션
        if (state.showDroneAnimation) {
            DroneAnimation(
                onAnimationEnd = {
                    mapViewModel.handleEvent(MapEvent.EndDroneAnimation)
                }
            )
        }

        // 3-7) 서비스 종료 결과 모달
        if (state.showServiceEndModal) {
            AlertDialog(
                onDismissRequest = {
                    mapViewModel.handleEvent(MapEvent.CloseAllModals)
                },
                title = {
                    Text(
                        "서비스 종료",
                        style = MaterialTheme.typography.subtitle1,
                    )
                },
                text = {
                    Text(
                        text = "서비스가 성공적으로 종료되었습니다.",
                        style = MaterialTheme.typography.body1,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            mapViewModel.handleEvent(MapEvent.CloseAllModals)
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colors.secondary
                        ),
                    ) {
                        Text(
                            text = "확인",
                            style = MaterialTheme.typography.subtitle2,
                        )
                    }
                }
            )
        }




        AlertModal(
            alertState = alertState,
            onDismiss = mapViewModel::dismissAlert,
            onEmergencyAlert = if (alertState.alertType == AlertType.WARNING) {
                {
                    coroutineScope.launch {
                        val success = mapViewModel.sendEmergencyAlert()
                        Log.d(TAG, "비상상황 요청: $success")
                        if (success) {
                            mapViewModel.showToast()
                        } else {
                            // TODO: API 호출 실패 시 에러 메시지 등의 추가 작업 수행
                        }
                    }
                    true
                }
            } else null,
            onSafeConfirm = {
                mapViewModel.updateWatchConfirmation(true)
                mapViewModel.dismissAlert()
            },
            alertHandler = alertHandler,
            safetyMessageSender = safetyMessageSender
        )

        ConnectionStatusSnackbar(
            connectionState = connectionState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // 심박수
        if (connectionState == WatchConnectionState.Connected || connectionState is WatchConnectionState.Connecting) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()  // 전체 너비를 가지도록
                    .padding(top = 170.dp), // 검색 필드 아래로 위치 조정
                contentAlignment = Alignment.CenterEnd // Box 내에서 오른쪽 정렬
            ) {
                Box(
                    modifier = Modifier.padding(end = 4.dp) // 오른쪽 여백 추가
                ) {
                    HeartRateDisplay(heartRate = heartRate)
                }
            }
        }
    }
}