package com.example.screenhandwritingoverlay

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.screenhandwritingoverlay.databinding.FragmentFirstBinding

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnGrantPermission.setOnClickListener {
            requestOverlayPermission()
        }

        binding.btnOpenAppInfo.setOnClickListener {
            openAppDetailsSettings()
        }

        binding.btnToggleOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(requireContext())) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.permission_overlay_title),
                    Toast.LENGTH_LONG
                ).show()
                requestOverlayPermission()
            } else {
                if (OverlayService.isRunning) {
                    OverlayService.stopService(requireContext())
                } else {
                    OverlayService.startService(requireContext())
                }
                view.postDelayed({ updateUIState() }, 300)
            }
        }

        binding.btnTestUndo.setOnClickListener {
            binding.testDrawingView.undo()
        }

        binding.btnTestClear.setOnClickListener {
            binding.testDrawingView.clear()
        }
    }

    override fun onResume() {
        super.onResume()
        updateUIState()
    }

    private fun updateUIState() {
        val context = context ?: return
        val hasPermission = Settings.canDrawOverlays(context)

        if (hasPermission) {
            binding.tvPermissionStatus.text = getString(R.string.status_permission_granted)
            binding.tvPermissionStatus.setTextColor(Color.parseColor("#2E7D32"))
            binding.btnGrantPermission.visibility = View.GONE
            binding.btnOpenAppInfo.visibility = View.GONE
            binding.cardRestrictedHelp.visibility = View.GONE
        } else {
            binding.tvPermissionStatus.text = getString(R.string.status_permission_denied)
            binding.tvPermissionStatus.setTextColor(Color.parseColor("#D32F2F"))
            binding.btnGrantPermission.visibility = View.VISIBLE
            binding.btnOpenAppInfo.visibility = View.VISIBLE
            binding.cardRestrictedHelp.visibility = View.VISIBLE
        }

        if (OverlayService.isRunning) {
            binding.btnToggleOverlay.text = getString(R.string.btn_stop_overlay)
            binding.btnToggleOverlay.setBackgroundColor(Color.parseColor("#424242"))
        } else {
            binding.btnToggleOverlay.text = getString(R.string.btn_start_overlay)
            binding.btnToggleOverlay.setBackgroundColor(Color.parseColor("#D32F2F"))
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${requireContext().packageName}")
        )
        startActivity(intent)
    }

    private fun openAppDetailsSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${requireContext().packageName}")
        )
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}